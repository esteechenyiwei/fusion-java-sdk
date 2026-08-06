package io.github.jpmorganchase.fusion.digest.checksum;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

public class DigestProviderService {

    public DigestProvider getDigestProvider(String digestAlgo) throws IOException {
        try {
            return switch (digestAlgo) {
                case "CRC32" -> new CRC32Provider();
                case "CRC32C" -> new CRC32CProvider();
                case "CRC64NVME" -> new CRC64NVMEProvider();
                case "SHA-1", "SHA-256", "MD5" -> new SHAProvider(digestAlgo);
                default -> throw new IOException("Invalid digest algorithm provided");
            };
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Invalid digest algorithm provided", e);
        }
    }
}
