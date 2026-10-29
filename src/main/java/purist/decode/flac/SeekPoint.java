package purist.decode.flac;

/**
 * One usable entry of a SEEKTABLE block (RFC 9639 section 8.5), placeholders already dropped.
 *
 * @param sample  the number of the first sample of the target frame
 * @param offset  bytes from the first byte of the first frame to the first byte of the target frame
 * @param samples how many samples the target frame holds
 */
public record SeekPoint(long sample, long offset, int samples) {}
