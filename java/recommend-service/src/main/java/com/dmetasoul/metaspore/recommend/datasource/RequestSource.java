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
package com.dmetasoul.metaspore.recommend.datasource;

import com.dmetasoul.metaspore.recommend.annotation.ServiceAnnotation;
import com.dmetasoul.metaspore.recommend.configure.FeatureConfig;
import com.dmetasoul.metaspore.recommend.data.DataContext;
import com.dmetasoul.metaspore.recommend.data.ServiceRequest;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
/**
 * source.kind=request的DataSource实现类
 * 配置中的source.kind需要与注解DataSourceAnnotation中value保持一致
 * Created by @author qinyy907 in 14:24 22/08/01.
 */
@Data
@Slf4j
@ServiceAnnotation("Request")
public class RequestSource extends DataSource {

    @Override
    public boolean initService() {
        FeatureConfig.Source source = taskFlowConfig.getSources().get(name);
        if (!source.getKind().equalsIgnoreCase("request")) {
            log.error("config request fail! is not kind:{} eq request!", source.getKind());
            return false;
        }
        return true;
    }

    @Override
    public void close() {}

    /**
     * 直接返回请求中的request参数
     * @param request datasource 相关请求
     * @param context 服务请求相关参数和任务的上下文信息数据
     * @return datasource获取的数据
     */
    @Override
    public List<Map<String, Object>> process(ServiceRequest request, DataContext context) {
        return List.of(context.getRequest());
    }
}
