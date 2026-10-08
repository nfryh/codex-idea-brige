// 模型上下文协议只声明已实现且受限的五项工具。
package dev.local.icb.contract;

import com.google.gson.*;

import java.util.*;

/** Model Context Protocol（模型上下文协议）的工具模式，身份不从参数读取。 */
public final class ToolCatalog {
    private ToolCatalog() {}

    /** 生成基础工具目录，不声明写文件或执行命令能力。 */
    public static JsonArray tools() {
        JsonArray tools = new JsonArray();
        tools.add(
                tool(
                        "ide_get_context",
                        "读取授权的活动编辑器元信息及有界选区",
                        Json.object(
                                "view", Json.object("type", "string", "enum", List.of("active"))),
                        List.of(),
                        true));
        JsonObject path =
                Json.object(
                        "rootId",
                        Json.object("type", "string"),
                        "relativePath",
                        Json.object("type", "string"));
        JsonObject read = path.deepCopy();
        read.add("startLine", Json.object("type", "integer", "minimum", 1));
        read.add("endLine", Json.object("type", "integer", "minimum", 1));
        tools.add(
                tool(
                        "ide_read_document",
                        "读取项目文档并注明来源和版本",
                        read,
                        List.of("rootId", "relativePath"),
                        true));
        tools.add(
                tool(
                        "ide_get_diagnostics",
                        "读取现有高亮并注明分析状态",
                        path,
                        List.of("rootId", "relativePath"),
                        true));
        JsonObject open = path.deepCopy();
        open.add("line", Json.object("type", "integer", "minimum", 1));
        open.add("column", Json.object("type", "integer", "minimum", 1));
        tools.add(
                tool(
                        "ide_open_file",
                        "在编辑器定位获准文件，不写入",
                        open,
                        List.of("rootId", "relativePath", "line", "column"),
                        false));
        tools.add(
                tool(
                        "ide_show_diff",
                        "显示已记录的只读比较，不应用补丁",
                        Json.object("reviewId", Json.object("type", "string")),
                        List.of("reviewId"),
                        false));
        return tools;
    }

    /**
     * 构造工具描述。
     *
     * @param name 本项目固定工具名称
     * @param description 工具实际用途说明
     * @param readOnly true 表示无界面或文件副作用，false 表示会打开文件或比较界面
     */
    private static JsonObject tool(
            String name,
            String description,
            JsonObject properties,
            List<String> required,
            boolean readOnly) {
        return Json.object(
                "name",
                name,
                "description",
                description,
                "inputSchema",
                Json.object(
                        "type",
                        "object",
                        "properties",
                        properties,
                        "required",
                        required,
                        "additionalProperties",
                        false),
                "annotations",
                Json.object(
                        "readOnlyHint",
                        readOnly,
                        "destructiveHint",
                        false,
                        "openWorldHint",
                        false));
    }

    /**
     * 获得工具失败结果，不使用 JSON-RPC 方法不存在表示业务失败。
     *
     * @param errorCode 固定脱敏错误码，不携带路径或异常堆栈
     */
    public static JsonObject error(String errorCode) {
        return Json.object(
                "content",
                List.of(Json.object("type", "text", "text", errorCode)),
                "isError",
                true);
    }

    /** 编码工具的正常业务结果。 */
    public static JsonObject result(JsonObject payload) {
        return Json.object(
                "content",
                List.of(Json.object("type", "text", "text", Json.GSON.toJson(payload))),
                "isError",
                false);
    }
}
