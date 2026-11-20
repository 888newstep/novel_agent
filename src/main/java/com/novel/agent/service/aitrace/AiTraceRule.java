package com.novel.agent.service.aitrace;

/**
 * 单条 AI 痕迹检测规则。
 *
 * <p>规则只读 {@link AiTraceContext}（文本切分等预处理已在上下文构造时完成），
 * 返回一条 {@link AiTraceFinding}。规则自身不做阈值配置读取以外的副作用。</p>
 */
public interface AiTraceRule {

    /** 规则编号，如 {@code R1}。 */
    String id();

    /** 所属检测维度。 */
    AiTraceDimension dimension();

    /** 对上下文执行检测，永不返回 {@code null}。 */
    AiTraceFinding evaluate(AiTraceContext context);
}
