package com.novel.agent.service.aitrace;

import com.novel.agent.config.AiTraceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * AI 痕迹词典加载器。
 *
 * <p>词典为 classpath 资源，UTF-8 编码；{@code #} 开头为注释，
 * 分组用注释行 {@code # group: <name>} 声明，词条一行一个：</p>
 *
 * <pre>
 * # group: banned
 * 突然
 * # group: cliche
 * 心中一凛
 * # group: transition
 * 但是
 * </pre>
 *
 * <p>加载失败时记录 warn 日志并降级为空分组：依赖该分组的规则返回
 * UNKNOWN，不影响应用启动。</p>
 */
@Slf4j
@Component
public class AiTraceDictionary {

    public static final String GROUP_BANNED = "banned";
    public static final String GROUP_CLICHE = "cliche";
    public static final String GROUP_TRANSITION = "transition";

    private final Map<String, List<String>> groups;

    public AiTraceDictionary(AiTraceProperties properties) {
        this.groups = load(properties.getDictionaryPath());
        log.info("AI trace dictionary loaded: groups={}, sizes={}",
                groups.keySet(), groups.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue().size())
                        .toList());
    }

    /** 返回指定分组的词条列表；分组不存在或加载失败时返回空列表。 */
    public List<String> group(String name) {
        return groups.getOrDefault(name, List.of());
    }

    private Map<String, List<String>> load(String dictionaryPath) {
        try (InputStream in = new ClassPathResource(dictionaryPath).getInputStream()) {
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            log.warn("AI trace dictionary '{}' failed to load, rules relying on it will degrade to UNKNOWN: {}",
                    dictionaryPath, e.getMessage());
            return Map.of();
        }
    }

    static Map<String, List<String>> parse(String content) {
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        String currentGroup = null;
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                if (line.startsWith("# group:")) {
                    currentGroup = line.substring("# group:".length()).trim();
                    result.putIfAbsent(currentGroup, new java.util.ArrayList<>());
                }
                continue;
            }
            if (currentGroup != null) {
                result.get(currentGroup).add(line);
            }
        }
        return result;
    }
}
