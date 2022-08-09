//
// Copyright 2022 DMetaSoul
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
package com.dmetasoul.metaspore.recommend.recommend;

import com.dmetasoul.metaspore.recommend.TaskServiceRegister;
import com.dmetasoul.metaspore.recommend.common.Utils;
import com.dmetasoul.metaspore.recommend.configure.Chain;
import com.dmetasoul.metaspore.recommend.configure.RecommendConfig;
import com.dmetasoul.metaspore.recommend.configure.TaskFlowConfig;
import com.dmetasoul.metaspore.recommend.data.DataContext;
import com.dmetasoul.metaspore.recommend.data.DataResult;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@SuppressWarnings("rawtypes")
@Data
@Slf4j
public class TaskFlow<Service extends BaseService> {
    protected String name;
    protected ExecutorService taskPool;
    protected TaskServiceRegister serviceRegister;
    protected TaskFlowConfig taskFlowConfig;
    protected List<Chain> chains;
    protected long timeout = 30000L;
    protected TimeUnit timeUnit = TimeUnit.MILLISECONDS;

    public void init(String name, TaskFlowConfig taskFlowConfig, TaskServiceRegister serviceRegister) {
        if (StringUtils.isEmpty(name)) {
            throw new RuntimeException("name is null, init fail!");
        }
        this.name = name;
        this.taskFlowConfig = taskFlowConfig;
        this.serviceRegister = serviceRegister;
        this.taskPool = serviceRegister.getTaskPool();
    }

    public void close() {}

    @SneakyThrows
    public CompletableFuture<List<DataResult>> execute(List<DataResult> data, Map<String, Service> serviceMap, DataContext context) {
        CompletableFuture<List<DataResult>> future = CompletableFuture.supplyAsync(() -> data);
        for (Chain chain : chains) {
            if (CollectionUtils.isNotEmpty(chain.getThen())) {
                for (String taskName : chain.getThen()) {
                    Service service = serviceMap.get(taskName);
                    Assert.notNull(service, "no found the service in then at : " + taskName);
                    future = future.thenApplyAsync(dataResult -> {
                        try {
                            return service.execute(dataResult, context).get(timeout, timeUnit);
                        } catch (InterruptedException | ExecutionException e) {
                            throw new RuntimeException(e);
                        }
                    }, taskPool);
                }
            }
            if (CollectionUtils.isNotEmpty(chain.getWhen())) {
                List<CompletableFuture<List<DataResult>>> whenList = Lists.newArrayList();
                for (String taskName : chain.getWhen()) {
                    Service service = serviceMap.get(taskName);
                    Assert.notNull(service, "no found the service in when at : " + taskName);
                    whenList.add(future.thenApplyAsync(dataResult -> {
                        try {
                            return service.execute(dataResult, context).get(timeout, timeUnit);
                        } catch (InterruptedException | ExecutionException e) {
                            throw new RuntimeException(e);
                        }
                    }, taskPool));
                }
                CompletableFuture<?> resultFuture;
                // 设置any or all
                if (chain.isAny()) {
                    resultFuture = CompletableFuture.anyOf(whenList.toArray(new CompletableFuture[]{}));
                } else {
                    resultFuture = CompletableFuture.allOf(whenList.toArray(new CompletableFuture[]{}));
                }
                future = resultFuture.thenApplyAsync(x->{
                    List<DataResult> result = Lists.newArrayList();
                    for (CompletableFuture<List<DataResult>> subFuture : whenList) {
                        try {
                            result.addAll(subFuture.get(timeout, timeUnit));
                        } catch (InterruptedException | ExecutionException | TimeoutException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    if (CollectionUtils.isEmpty(result)) {
                        throw new RuntimeException("when execute fail");
                    }
                    return result;
                }, taskPool);
            }
        }
        return future;
    }
}
