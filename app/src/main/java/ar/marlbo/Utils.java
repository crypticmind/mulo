package ar.marlbo;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public class Utils {

    public static final Logger LOGGER = Logger.getLogger("mulo");

    private Utils() {}

    public static Map<String, String> parseFormUrlEncoded(String encodedString, String encoding)
            throws UnsupportedEncodingException {
        var map = new HashMap<String, String>();
        if (encodedString == null || encodedString.isEmpty()) {
            return map;
        }
        var pairs = encodedString.split("&");
        for (var pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx != -1) {
                var key = pair.substring(0, idx);
                var value = pair.substring(idx + 1);
                var decodedKey = URLDecoder.decode(key, encoding);
                var decodedValue = URLDecoder.decode(value, encoding);
                map.put(decodedKey, decodedValue);
            } else {
                var decodedKey = URLDecoder.decode(pair, encoding);
                map.put(decodedKey, "");
            }
        }
        return map;
    }

    public static String generateId() {
        return UUID.randomUUID().toString();
    }

    public static void require(boolean cond, String message) {
        if (!cond) {
            throw new IllegalArgumentException(message);
        }
    }

    public static int readInt(String input, String message) {
        try {
            return Integer.parseInt(input != null ? input.trim() : "");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(message);
        }
    }
}
