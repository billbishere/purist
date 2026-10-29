package purist.win;

/** A COM call that returned a failure {@code HRESULT}. */
public final class ComException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int hresult;

    public ComException(String operation, int hresult) {
        super("%s failed with HRESULT 0x%08X".formatted(operation, hresult));
        this.hresult = hresult;
    }

    /** The failing {@code HRESULT}, as the signed 32-bit value COM returned. */
    public int hresult() {
        return hresult;
    }
}
