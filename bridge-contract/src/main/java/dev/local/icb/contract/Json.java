// 严格解析桥接 JSON，禁止重复字段及宽松语法。
package dev.local.icb.contract;

import com.google.gson.*;
import com.google.gson.stream.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** 桥接协议的严格 JSON 编码和容量校验。 */
public final class Json {
    /** 统一编码器，不将平台对象传入编码器。 */
    public static final Gson GSON =
            new GsonBuilder()
                    .disableHtmlEscaping()
                    .serializeNulls()
                    .registerTypeAdapter(
                            java.time.Instant.class,
                            (JsonSerializer<java.time.Instant>)
                                    (value, type, context) -> new JsonPrimitive(value.toString()))
                    .create();

    private Json() {}

    /**
     * 解析单个对象，拒绝重复键、尾随正文和超过 64 层的嵌套。
     *
     * @param text 来自桥接传输的 JSON 正文
     */
    public static JsonObject parse(String text) {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            // 递归校验字段，避免认证后出现不同解析器解释不同的消息。
            JsonElement value = read(reader, 0);
            if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
                throw new IllegalArgumentException("JSON_INVALID");
            return value.getAsJsonObject();
        } catch (IOException | IllegalStateException ex) {
            throw new IllegalArgumentException("JSON_INVALID", ex);
        }
    }

    /**
     * 读取并验证一个 JSON 值。
     *
     * @param depth 当前 JSON 嵌套层数，最多 64 层
     */
    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 64) throw new IllegalArgumentException("JSON_DEPTH");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject value = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (value.has(name)) throw new IllegalArgumentException("JSON_DUPLICATE_KEY");
                    value.add(name, read(reader, depth + 1));
                }
                reader.endObject();
                return value;
            }
            case BEGIN_ARRAY -> {
                JsonArray value = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) value.add(read(reader, depth + 1));
                reader.endArray();
                return value;
            }
            case STRING -> {
                return new JsonPrimitive(reader.nextString());
            }
            case NUMBER -> {
                return new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            }
            case BOOLEAN -> {
                return new JsonPrimitive(reader.nextBoolean());
            }
            case NULL -> {
                reader.nextNull();
                return JsonNull.INSTANCE;
            }
            default -> throw new IllegalArgumentException("JSON_INVALID");
        }
    }

    /**
     * 获得必填非空字符串。
     *
     * @param key 协议对象的必填字段名称
     */
    public static String required(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank())
            throw new IllegalArgumentException("FIELD_REQUIRED:" + key);
        return value.getAsString();
    }

    /**
     * 创建只含指定键值的协议对象。
     *
     * @param pairs 依次排列的字段名和自有协议数据值
     */
    public static JsonObject object(Object... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2)
            result.add((String) pairs[i], GSON.toJsonTree(pairs[i + 1]));
        return result;
    }

    /**
     * 计算传输文本的 UTF-8 字节数。
     *
     * @param text 上下文或协议正文
     */
    public static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 计算不可逆摘要，摘要用于去重及并发冲突检查。
     *
     * @param text 快照正文或配置正文，不输出原文
     */
    public static String sha(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }

    /**
     * 有界读取输入，超量立即拒绝。
     *
     * @param limit 当前事件或响应允许的最大字节数
     */
    public static byte[] bounded(InputStream input, int limit) throws IOException {
        byte[] data = input.readNBytes(limit + 1);
        if (data.length > limit) throw new IOException("ICB_CONTEXT_TOO_LARGE");
        return data;
    }
}
