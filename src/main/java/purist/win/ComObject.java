package purist.win;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * A raw COM interface pointer. Vtable entries are bound to method handles that already carry the
 * function pointer and {@code this}, so callers invoke them with the interface's own arguments only.
 *
 * <p>Handles are cached per vtable slot, so {@link #method} is cheap and allocation-free once a slot
 * has been bound; it is safe to call from any thread, including a driver's audio callback thread.
 */
public final class ComObject {

    /** Slots visible through the vtable segment; comfortably above IUnknown plus the 24 IASIO methods. */
    private static final int VTABLE_SLOTS = 64;
    private static final int RELEASE_SLOT = 2;
    private static final FunctionDescriptor RELEASE = FunctionDescriptor.of(JAVA_INT);

    private record Bound(FunctionDescriptor descriptor, MethodHandle handle) {
    }

    private final MemorySegment ptr;
    private final MemorySegment vtbl;
    private final AtomicReferenceArray<Bound> bound = new AtomicReferenceArray<>(VTABLE_SLOTS);

    /** Wraps an interface pointer (a pointer to a pointer to the vtable). */
    public ComObject(MemorySegment iface) {
        Objects.requireNonNull(iface, "iface");
        if (iface.address() == 0) {
            throw new IllegalArgumentException("null COM interface pointer");
        }
        this.ptr = iface.reinterpret(ADDRESS.byteSize());
        this.vtbl = ptr.get(ADDRESS, 0).reinterpret(ADDRESS.byteSize() * VTABLE_SLOTS);
    }

    /** The interface pointer, passed as {@code this} to every method. */
    public MemorySegment ptr() {
        return ptr;
    }

    /**
     * The vtable entry {@code slot} (0 = QueryInterface, 1 = AddRef, 2 = Release) as a handle taking
     * {@code fdWithoutThis}'s arguments; {@code this} is already bound. Invoke it with
     * {@code invokeExact}, casting the result to exactly the descriptor's return type.
     *
     * @throws IllegalArgumentException if the slot was already bound with a different descriptor
     */
    public MethodHandle method(int slot, FunctionDescriptor fdWithoutThis) {
        Objects.checkIndex(slot, VTABLE_SLOTS);
        Bound existing = bound.get(slot);
        if (existing == null) {
            Bound created = new Bound(fdWithoutThis, bind(slot, fdWithoutThis));
            existing = bound.compareAndExchange(slot, null, created);
            if (existing == null) {
                return created.handle();
            }
        }
        if (!existing.descriptor().equals(fdWithoutThis)) {
            throw new IllegalArgumentException("vtable slot " + slot + " is already bound as "
                    + existing.descriptor() + ", cannot rebind as " + fdWithoutThis);
        }
        return existing.handle();
    }

    /** Calls {@code IUnknown::Release}. The object must not be used again once this returns 0. */
    public int release() {
        try {
            return (int) method(RELEASE_SLOT, RELEASE).invokeExact();
        } catch (Throwable t) {
            throw Native.unchecked("IUnknown::Release", t);
        }
    }

    private MethodHandle bind(int slot, FunctionDescriptor fdWithoutThis) {
        MemorySegment fnPtr = vtbl.getAtIndex(ADDRESS, slot);
        if (fnPtr.address() == 0) {
            throw new IllegalStateException("vtable slot " + slot + " is null");
        }
        List<MemoryLayout> declared = fdWithoutThis.argumentLayouts();
        MemoryLayout[] args = new MemoryLayout[declared.size() + 1];
        args[0] = ADDRESS; // `this`
        for (int i = 0; i < declared.size(); i++) {
            args[i + 1] = declared.get(i);
        }
        FunctionDescriptor withThis = fdWithoutThis.returnLayout()
                .map(r -> FunctionDescriptor.of(r, args))
                .orElseGet(() -> FunctionDescriptor.ofVoid(args));
        return MethodHandles.insertArguments(Native.LINKER.downcallHandle(withThis), 0, fnPtr, ptr);
    }
}
