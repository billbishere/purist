package purist.asio;

/** An installed ASIO driver: the registry key name and the braced CLSID that creates it. */
public record AsioDriverInfo(String name, String clsid) {
}
