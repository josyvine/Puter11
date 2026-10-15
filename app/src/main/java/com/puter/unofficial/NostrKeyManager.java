package com.puter.unofficial;

import android.util.Log;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.security.Security;

/**
 * Cryptographic Utility for Nostr Identity.
 * Responsible for generating Secp256k1 keypairs compatible with the 
 * decentralized Nostr protocol (BIP-340 Schnorr signatures).
 * Ensures Y-coordinate parity check to produce valid BIP-340 keys.
 * Updated to natively support Bech32 NIP-19 encoding and BIP-340 Schnorr signing.
 */
public class NostrKeyManager {

    private static final String TAG = "Puter_KeyManager";
    private static final String BECH32_ALPHABET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int[] BECH32_GENERATOR = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};

    static {
        // Register BouncyCastle as a security provider for Elliptic Curve operations
        Security.removeProvider("BC");
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * Generates a new 32-byte private key and its corresponding 32-byte 
     * x-only public key, returning them as Bech32-encoded NIP-19 strings.
     * 
     * @return String array: [0] = Private Key (Bech32 nsec), [1] = Public Key (Bech32 npub)
     */
    public static String[] generateKeyPair() {
        try {
            // 1. Setup the Secp256k1 Curve parameters
            X9ECParameters params = CustomNamedCurves.getByName("secp256k1");
            BigInteger n = params.getN();

            // 2. Generate a cryptographically secure 32-byte random number (The Private Key)
            SecureRandom secureRandom = new SecureRandom();
            BigInteger privateKeyInt;
            do {
                privateKeyInt = new BigInteger(256, secureRandom);
            } while (privateKeyInt.compareTo(n) >= 0 || privateKeyInt.equals(BigInteger.ZERO));

            // 3. Derive the Public Key Point (P = k * G)
            ECPoint publicKeyPoint = params.getG().multiply(privateKeyInt).normalize();

            // 4. BIP-340 / Nostr Requirement
            // Check if the Y-coordinate is ODD. If it is, we must negate the private key.
            // This ensures we always use the 'Even' coordinate version of the identity.
            if (publicKeyPoint.getAffineYCoord().toBigInteger().testBit(0)) {
                privateKeyInt = n.subtract(privateKeyInt);
                // Recalculate point (optional for X, but ensures point consistency)
                publicKeyPoint = params.getG().multiply(privateKeyInt).normalize();
            }

            // 5. Extract the X-coordinate (Nostr uses 32-byte x-only public keys)
            byte[] publicKeyBytes = publicKeyPoint.getAffineXCoord().getEncoded();
            if (publicKeyBytes.length > 32) {
                byte[] tmp = new byte[32];
                System.arraycopy(publicKeyBytes, publicKeyBytes.length - 32, tmp, 0, 32);
                publicKeyBytes = tmp;
            }

            // 6. Ensure raw 32-byte private key array (Handling sign-byte 0x00)
            byte[] rawPrivKey = privateKeyInt.toByteArray();
            if (rawPrivKey.length == 33 && rawPrivKey[0] == 0) {
                byte[] cleanPrivKey = new byte[32];
                System.arraycopy(rawPrivKey, 1, cleanPrivKey, 0, 32);
                rawPrivKey = cleanPrivKey;
            } else if (rawPrivKey.length < 32) {
                byte[] paddedPrivKey = new byte[32];
                System.arraycopy(rawPrivKey, 0, paddedPrivKey, 32 - rawPrivKey.length, rawPrivKey.length);
                rawPrivKey = paddedPrivKey;
            }

            // 7. Convert raw keys natively to BIP-19 compliant Bech32 formats
            String privateKeyBech32 = bech32Encode("nsec", rawPrivKey);
            String publicKeyBech32 = bech32Encode("npub", publicKeyBytes);

            Log.i(TAG, "Identity Generation Success. PubKey: " + publicKeyBech32);
            return new String[]{privateKeyBech32, publicKeyBech32};

        } catch (Exception e) {
            Log.e(TAG, "Cryptographic failure during key generation: " + e.getMessage());
            throw new RuntimeException("Identity Generation Failed: " + e.getMessage(), e);
        }
    }

    // ==========================================
    // BIP-340 SCHNORR SIGNATURE GENERATION ENGINE
    // ==========================================

    /**
     * BIP-340 Tagged Hash helper.
     * Computes SHA256(SHA256(tag) || SHA256(tag) || data)
     */
    public static byte[] taggedHash(String tag, byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] tagBytes = tag.getBytes("UTF-8");
            byte[] tagHash = digest.digest(tagBytes);
            byte[] combined = new byte[tagHash.length * 2 + data.length];
            System.arraycopy(tagHash, 0, combined, 0, tagHash.length);
            System.arraycopy(tagHash, 0, combined, tagHash.length, tagHash.length);
            System.arraycopy(data, 0, combined, tagHash.length * 2, data.length);
            return digest.digest(combined);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 generation failed in taggedHash", e);
        }
    }

    /**
     * BIP-340 Schnorr Signature Generation (for 32-byte message hashes).
     *
     * @param privateKeyBytes 32-byte raw private key
     * @param messageBytes 32-byte message hash (e.g. Nostr event ID)
     * @return 64-byte signature array (R_x || s)
     */
    public static byte[] signBIP340(byte[] privateKeyBytes, byte[] messageBytes) {
        try {
            X9ECParameters params = CustomNamedCurves.getByName("secp256k1");
            BigInteger n = params.getN();
            BigInteger d = new BigInteger(1, privateKeyBytes);
            if (d.compareTo(BigInteger.ZERO) == 0 || d.compareTo(n) >= 0) {
                throw new IllegalArgumentException("Key exceeds coordinate bounds.");
            }

            // Compute Public Point P = d * G
            ECPoint P = params.getG().multiply(d).normalize();
            if (P.getAffineYCoord().toBigInteger().testBit(0)) {
                d = n.subtract(d); // Negate private key if public point coordinate y is odd
                P = params.getG().multiply(d).normalize();
            }

            byte[] pubKeyXBytes = P.getAffineXCoord().getEncoded();
            if (pubKeyXBytes.length > 32) {
                byte[] tmp = new byte[32];
                System.arraycopy(pubKeyXBytes, pubKeyXBytes.length - 32, tmp, 0, 32);
                pubKeyXBytes = tmp;
            }

            // Generate deterministic nonce k using BIP-340 tagged hash of (d || m)
            byte[] combinedInput = new byte[32 + 32];
            System.arraycopy(privateKeyBytes, 0, combinedInput, 0, 32);
            System.arraycopy(messageBytes, 0, combinedInput, 32, 32);

            byte[] nonceHash = taggedHash("BIP0340/nonce", combinedInput);
            BigInteger k = new BigInteger(1, nonceHash).mod(n);
            if (k.compareTo(BigInteger.ZERO) == 0) {
                k = BigInteger.ONE;
            }

            // Compute commitment point R = k * G
            ECPoint R = params.getG().multiply(k).normalize();
            if (R.getAffineYCoord().toBigInteger().testBit(0)) {
                k = n.subtract(k); // Negate nonce coordinate if R y is odd
                R = params.getG().multiply(k).normalize();
            }

            byte[] rBytes = R.getAffineXCoord().getEncoded();
            if (rBytes.length > 32) {
                byte[] tmp = new byte[32];
                System.arraycopy(rBytes, rBytes.length - 32, tmp, 0, 32);
                rBytes = tmp;
            }

            // Compute challenge hash e = tagged_hash("BIP0340/challenge", R_x || P_x || m)
            byte[] challengeInput = new byte[32 + 32 + 32];
            System.arraycopy(rBytes, 0, challengeInput, 0, 32);
            System.arraycopy(pubKeyXBytes, 0, challengeInput, 32, 32);
            System.arraycopy(messageBytes, 0, challengeInput, 64, 32);

            byte[] eHash = taggedHash("BIP0340/challenge", challengeInput);
            BigInteger e = new BigInteger(1, eHash).mod(n);

            // Compute s parameter: s = (k + e * d) % n
            BigInteger s = k.add(e.multiply(d)).mod(n);
            byte[] sBytes = s.toByteArray();
            byte[] cleanSBytes = new byte[32];
            if (sBytes.length > 32) {
                System.arraycopy(sBytes, sBytes.length - 32, cleanSBytes, 0, 32);
            } else {
                System.arraycopy(sBytes, 0, cleanSBytes, 32 - sBytes.length, sBytes.length);
            }

            // Combine R_x and s to form 64-byte signature
            byte[] signature = new byte[64];
            System.arraycopy(rBytes, 0, signature, 0, 32);
            System.arraycopy(cleanSBytes, 0, signature, 32, 32);

            return signature;
        } catch (Exception e) {
            Log.e(TAG, "BIP-340 signing failed: " + e.getMessage());
            throw new RuntimeException("Signing failed", e);
        }
    }

    // ==========================================
    // NATIVE BECH32 ENCODER AND DECODER ENGINE
    // ==========================================

    /**
     * Encodes raw byte data to Bech32 format with a given human-readable prefix.
     */
    public static String bech32Encode(String hrp, byte[] data) {
        byte[] converted = convertBits(data, 8, 5, true);
        byte[] checksum = bech32CreateChecksum(hrp, converted);
        byte[] combined = new byte[converted.length + checksum.length];
        System.arraycopy(converted, 0, combined, 0, converted.length);
        System.arraycopy(checksum, 0, combined, converted.length, checksum.length);

        StringBuilder sb = new StringBuilder();
        sb.append(hrp).append('1');
        for (byte b : combined) {
            sb.append(BECH32_ALPHABET.charAt(b));
        }
        return sb.toString();
    }

    /**
     * Decodes a Bech32 string to raw bytes and verifies its human-readable prefix.
     */
    public static byte[] bech32Decode(String bechString, String expectedHrp) {
        int separatorIndex = bechString.lastIndexOf('1');
        if (separatorIndex == -1) {
            throw new IllegalArgumentException("Invalid Bech32 formatting: Missing separator character '1'.");
        }
        String hrp = bechString.substring(0, separatorIndex);
        if (!hrp.equals(expectedHrp)) {
            throw new IllegalArgumentException("HRP mismatch: Expected prefix '" + expectedHrp + "', got '" + hrp + "'.");
        }

        String dataString = bechString.substring(separatorIndex + 1);
        byte[] data = new byte[dataString.length()];
        for (int i = 0; i < dataString.length(); i++) {
            int charIndex = BECH32_ALPHABET.indexOf(dataString.charAt(i));
            if (charIndex == -1) {
                throw new IllegalArgumentException("Bech32 contains prohibited structures at character: " + dataString.charAt(i));
            }
            data[i] = (byte) charIndex;
        }

        if (!bech32VerifyChecksum(hrp, data)) {
            throw new IllegalArgumentException("Invalid Bech32 checksum verification failed.");
        }

        byte[] values = new byte[data.length - 6];
        System.arraycopy(data, 0, values, 0, values.length);
        return convertBits(values, 5, 8, false);
    }

    private static int bech32Polymod(byte[] values) {
        int chk = 1;
        for (byte value : values) {
            int top = chk >> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ (value & 0xff);
            for (int i = 0; i < 5; i++) {
                if (((top >> i) & 1) != 0) {
                    chk ^= BECH32_GENERATOR[i];
                }
            }
        }
        return chk;
    }

    private static byte[] bech32HrpExpand(String hrp) {
        int len = hrp.length();
        byte[] ret = new byte[len * 2 + 1];
        for (int i = 0; i < len; i++) {
            char c = hrp.charAt(i);
            ret[i] = (byte) (c >> 5);
            ret[len + 1 + i] = (byte) (c & 31);
        }
        ret[len] = 0;
        return ret;
    }

    private static byte[] bech32CreateChecksum(String hrp, byte[] data) {
        byte[] hrpExpanded = bech32HrpExpand(hrp);
        byte[] values = new byte[hrpExpanded.length + data.length + 6];
        System.arraycopy(hrpExpanded, 0, values, 0, hrpExpanded.length);
        System.arraycopy(data, 0, values, hrpExpanded.length, data.length);
        int polymod = bech32Polymod(values) ^ 1;
        byte[] ret = new byte[6];
        for (int i = 0; i < 6; i++) {
            ret[i] = (byte) ((polymod >> (5 * (5 - i))) & 31);
        }
        return ret;
    }

    private static boolean bech32VerifyChecksum(String hrp, byte[] data) {
        byte[] hrpExpanded = bech32HrpExpand(hrp);
        byte[] values = new byte[hrpExpanded.length + data.length];
        System.arraycopy(hrpExpanded, 0, values, 0, hrpExpanded.length);
        System.arraycopy(data, 0, values, hrpExpanded.length, data.length);
        return bech32Polymod(values) == 1;
    }

    private static byte[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0;
        int bits = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int maxv = (1 << toBits) - 1;
        for (byte b : data) {
            int value = b & 0xff;
            acc = (acc << fromBits) | value;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                out.write((acc >> bits) & maxv);
            }
        }
        if (pad) {
            if (bits > 0) {
                out.write((acc << (toBits - bits)) & maxv);
            }
        } else if (bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0) {
            throw new IllegalArgumentException("Could not convert bits cleanly due to leftover unaligned bits.");
        }
        return out.toByteArray();
    }

    /**
     * Helper to normalize hexadecimal strings to exact length, padding with zeroes if required.
     */
    private static String normalizeHex(String hex, int length) {
        if (hex == null) return "";
        if (hex.length() > length) {
            return hex.substring(hex.length() - length);
        } else if (hex.length() < length) {
            StringBuilder sb = new StringBuilder();
            while (sb.length() + hex.length() < length) {
                sb.append("0");
            }
            sb.append(hex);
            return sb.toString();
        }
        return hex;
    }

    /**
     * Converts a hexadecimal string back to a byte array.
     */
    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        if (hex.length() % 2 != 0) hex = "0" + hex;
        
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Converts a byte array to a hexadecimal string.
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}