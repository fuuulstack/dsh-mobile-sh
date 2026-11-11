package com.dsh.mobile.v2.net

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 宿主 RPC 失败的中文文案（v2）。
 *
 * 宿主把失败编码成 `{ok:false, error:{code, message, details}}`；message 是英文诊断串，
 * code 是稳定标识——按 code 查表翻中文，英文原文只进 Diag 备查；表里没有的 code
 * 也给出中文骨架，不退回英文。
 */
object HostErrors {

    /** 从宿主的 error 对象取中文原因；一定非空。 */
    fun message(error: JsonElement?): String {
        if (error == null || error !is JsonObject) return "DSH 拒绝了请求"
        val code = (error["code"] as? JsonPrimitive)?.content ?: ""
        val zh = text(code)
        if (zh != null) return zh
        if (code.isEmpty()) return "DSH 请求失败"
        // 没收录的 code：保留 code 便于对照桌面端，但句子本身是中文。
        return "DSH 请求失败（$code）"
    }

    /** 宿主 error 对象的英文原文，只用于写诊断日志。 */
    fun original(error: JsonElement?): String {
        if (error == null || error !is JsonObject) return ""
        val code = (error["code"] as? JsonPrimitive)?.content ?: ""
        val raw = (error["message"] as? JsonPrimitive)?.content ?: ""
        if (code.isEmpty()) return raw
        return if (raw.isEmpty()) code else "$code: $raw"
    }

    /** code → 中文。返回 null 表示这张表不认这个 code。 */
    fun text(code: String?): String? = when (code) {
        // 会话
        "session/not-found" -> "会话不存在或已被删除"
        "session/agent-busy" -> "上一轮还在运行，请稍候再试"
        "session/conflict" -> "会话状态已变化，请刷新后重试"
        "session/writer-held" -> "这个会话正被另一个客户端占用"
        "session/model-unavailable" -> "请先选择一个可用模型再发送"
        "session/provider-credentials-unavailable" -> "模型凭据不可用，请在电脑端检查"
        "session/provider-models-unavailable" -> "模型列表拉不到，请在电脑端检查"
        "session/projections-unavailable" -> "会话详情暂不可用"
        "session/attachment-invalid", "session/attachment" -> "附件不可用，请重新选择"
        "session/title-invalid" -> "会话标题不合法"
        "session/invalid-time-zone" -> "设备时区不被支持"
        "session/migration-required" -> "源会话需要先完成迁移才能分叉"
        "session/fork-unavailable" -> "这个会话暂时无法分叉"
        "session/workspace-attach-failed" -> "工作区挂载失败"
        "session/queue-item-not-found" -> "这条排队消息已不在队列里"
        "session/steer-unavailable" -> "当前轮已不再接受插话"
        "session/cancel" -> "取消请求失败"
        "session/prompt" -> "消息发送失败"
        "session/rename" -> "重命名失败"
        "session/search" -> "搜索失败"
        "session/page" -> "加载更多消息失败"
        "session/list" -> "拉取会话列表失败"
        "session/create" -> "新建会话失败"
        "session/follow" -> "打开会话失败"
        "session/projections" -> "会话详情暂不可用"
        "session/control" -> "会话控制失败"

        // 工作区与计划
        "workspace/not-found" -> "工作区不存在"
        "workspace/name-conflict" -> "已有同名工作区"
        "workspace/invalid-path" -> "无法在该路径创建工作区"
        "plan/not-found" -> "计划不存在"
        "plan/unavailable" -> "会话历史里已找不到这份计划"

        // 网关基础设施
        "gateway/bad-request", "gateway/arguments-invalid", "gateway/input-invalid" ->
            "请求参数不合法，请更新 App 或联系管理员"
        "gateway/cancelled" -> "请求已取消"
        "gateway/method-unavailable", "gateway/definition-unavailable" -> "电脑端 DSH 版本太旧，没有这个接口"
        "gateway/invocation-unavailable" -> "这个接口当前不可调用"
        "gateway/lookup-not-found" -> "电脑端找不到这个接口"
        "gateway/lookup-unavailable" -> "电脑端接口不可用"
        "gateway/lookup-failed" -> "电脑端接口调用失败"
        "gateway/context-not-found" -> "目标会话在电脑端已不存在"
        "gateway/context-unavailable" -> "目标会话暂不可用"
        "gateway/context-failed" -> "电脑端处理上下文时出错"
        "gateway/peer-disposed" -> "与电脑端的连接已断开"
        "gateway/binding-invalid" -> "与电脑端的连接已失效，请重新配对"
        "gateway/service-unavailable" -> "电脑端服务暂不可用"
        "gateway/ambiguous-endpoint" -> "电脑端接口定义有冲突"
        "gateway/interactions-forbidden" -> "这个操作不能在手机上发起"
        "gateway/provider-mismatch" -> "电脑端提供方不匹配"
        "gateway/result-invalid" -> "电脑端返回的结果格式不对"
        "gateway/signature-invalid" -> "电脑端调用签名无效"
        "gateway/uplink-overflow" -> "上行数据超出上限"
        "gateway/protocol" -> "与电脑端的协议不兼容，请更新 App"
        "gateway/internal" -> "电脑端内部错误"
        else -> null
    }
}
