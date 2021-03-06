package org.jetlinks.community.rule.engine.executor;

import com.alibaba.fastjson.JSONObject;
import lombok.AllArgsConstructor;
import org.jetlinks.community.gateway.MessageGateway;
import org.jetlinks.community.gateway.Subscription;
import org.jetlinks.community.gateway.TopicMessage;
import org.jetlinks.reactor.ql.ReactorQL;
import org.jetlinks.rule.engine.api.RuleConstants;
import org.jetlinks.rule.engine.api.RuleData;
import org.jetlinks.rule.engine.api.RuleDataHelper;
import org.jetlinks.rule.engine.api.model.RuleNodeModel;
import org.jetlinks.rule.engine.api.task.ExecutionContext;
import org.jetlinks.rule.engine.api.task.TaskExecutor;
import org.jetlinks.rule.engine.api.task.TaskExecutorProvider;
import org.jetlinks.rule.engine.defaults.AbstractTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
@AllArgsConstructor
public class ReactorQLTaskExecutorProvider implements TaskExecutorProvider {

    private final MessageGateway messageGateway;

    @Override
    public String getExecutor() {
        return "reactor-ql";
    }

    @Override
    public Mono<TaskExecutor> createTask(ExecutionContext context) {
        return Mono.just(new ReactorQLTaskExecutor(context));
    }

    class ReactorQLTaskExecutor extends AbstractTaskExecutor {

        private ReactorQL reactorQL;

        public ReactorQLTaskExecutor(ExecutionContext context) {
            super(context);
            reactorQL = createQl();
        }

        @Override
        public String getName() {
            return "ReactorQL";
        }

        @Override
        protected Disposable doStart() {
            Disposable.Composite composite = Disposables.composite();
            Flux<Map<String, Object>> dataStream;
            //有上游节点
            if (!CollectionUtils.isEmpty(context.getJob().getInputs())) {

                dataStream = context.getInput()
                    .accept()
                    .map(RuleDataHelper::toContextMap)
                    .flatMap(v -> reactorQL.start(Flux.just(v))
                        .onErrorResume(err -> {
                            context.getLogger().error(err.getMessage(),err);
                            return context.onError(err, null).then(Mono.empty());
                        }))
                ;
            } else {
                dataStream = reactorQL
                    .start(table -> {
                        if (table == null || table.equalsIgnoreCase("dual")) {
                            return Flux.just(1);
                        }
                        if (table.startsWith("/")) {
                            //转换为消息
                            return messageGateway
                                .subscribe(
                                    Collections.singleton(new Subscription(table)),
                                    "rule-engine:".concat(context.getInstanceId()),
                                    false)
                                .map(TopicMessage::convertMessage);
                        }
                        return Flux.just(1);
                    });
            }

            return dataStream
                .flatMap(result -> {
                    RuleData data = context.newRuleData(result);
                    //输出到下一节点
                    return context.getOutput()
                        .write(Mono.just(data))
                        .then(context.fireEvent(RuleConstants.Event.result, data));
                })
                .onErrorResume(err -> context.onError(err, null))
                .subscribe();
        }

        protected ReactorQL createQl() {
            ReactorQL.Builder builder = Optional.ofNullable(context.getJob().getConfiguration())
                .map(map -> map.get("sql"))
                .map(String::valueOf)
                .map(ReactorQL.builder()::sql)
                .orElseThrow(() -> new IllegalArgumentException("配置sql错误"));
            return builder.build();
        }

        @Override
        public void reload() {
            reactorQL = createQl();
            if (this.disposable != null) {
                this.disposable.dispose();
            }
            start();
        }

        @Override
        public void validate() {
            createQl();
        }
    }
}
