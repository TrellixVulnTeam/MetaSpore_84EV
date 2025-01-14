package com.dmetasoul.metaspore.recommend.recommend;

import com.dmetasoul.metaspore.recommend.baseservice.TaskServiceRegister;
import com.dmetasoul.metaspore.recommend.common.CommonUtils;
import com.dmetasoul.metaspore.recommend.configure.TransformConfig;
import com.dmetasoul.metaspore.recommend.data.DataContext;
import com.dmetasoul.metaspore.recommend.data.DataResult;
import com.dmetasoul.metaspore.recommend.enums.DataTypeEnum;
import com.dmetasoul.metaspore.recommend.recommend.interfaces.MergeOperator;
import com.dmetasoul.metaspore.recommend.recommend.interfaces.TransformFunction;
import com.dmetasoul.metaspore.recommend.recommend.interfaces.UpdateOperator;
import com.dmetasoul.metaspore.serving.ArrowAllocator;
import com.dmetasoul.metaspore.serving.FeatureTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.springframework.util.Assert;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static com.dmetasoul.metaspore.recommend.configure.ColumnInfo.getField;
import static com.dmetasoul.metaspore.recommend.configure.ColumnInfo.getType;

@SuppressWarnings("unchecked")
@Slf4j
public abstract class Transform {
    private String name;
    private ExecutorService taskPool;
    private TaskServiceRegister serviceRegister;
    protected Map<String, TransformFunction> transformFunctions;
    protected Map<String, MergeOperator> mergeOperators;
    protected Map<String, UpdateOperator> updateOperators;

    protected List<Field> resFields;
    protected List<DataTypeEnum> dataTypes;

    public static final int DEFAULT_MAX_RESERVATION = 50;

    public void initTransform(String name, ExecutorService taskPool, TaskServiceRegister serviceRegister) {
        this.name = name;
        this.taskPool = taskPool;
        this.serviceRegister = serviceRegister;
        this.transformFunctions = Maps.newHashMap();
        this.mergeOperators = Maps.newHashMap();
        this.updateOperators = Maps.newHashMap();
        initFunctions();
        addFunctions();
    }

    public void addFunctions() {
        addFunction("summary", (data, results, context, option) -> {
            Assert.isTrue(CollectionUtils.isNotEmpty(resFields), "summary need configure columns info!");
            DataResult result = new DataResult();
            FeatureTable featureTable = new FeatureTable(name, resFields, ArrowAllocator.getAllocator());
            result.setFeatureTable(featureTable);
            result.setDataTypes(dataTypes);
            result.setName(name);
            List<String> dupFields = getOptionFields("dupFields", option);
            result.mergeDataResult(data, dupFields, getMergeOperators(option), option);
            featureTable.finish();
            results.add(result);
            return true;
        });
        addFunction("summaryBySchema", (data, results, context, option) -> {
            if (CollectionUtils.isNotEmpty(data)) {
                DataResult item = data.get(0);
                DataResult result = new DataResult();
                result.setName(name);
                FeatureTable featureTable = new FeatureTable(String.format("%s.summaryBySchema", name), item.getFields(), ArrowAllocator.getAllocator());
                result.setFeatureTable(featureTable);
                result.setDataTypes(item.getDataTypes());
                List<String> dupFields = getOptionFields("dupFields", option);
                result.mergeDataResult(data, dupFields, getMergeOperators(option), option);
                featureTable.finish();
                results.add(result);
            }
            return true;
        });
        addFunction("orderAndLimit", (data, results, context, option) -> {
            if (CollectionUtils.isNotEmpty(data)) {
                for (DataResult item : data) {
                    DataResult result = new DataResult();
                    FeatureTable featureTable = new FeatureTable(item.getFeatureTable().getName(), item.getFields(), ArrowAllocator.getAllocator());
                    result.setFeatureTable(featureTable);
                    result.setDataTypes(item.getDataTypes());
                    List<String> orderFields = getOptionFields("orderFields", option);
                    int limit = CommonUtils.getField(option, "maxReservation", DEFAULT_MAX_RESERVATION);
                    result.orderAndLimit(item, orderFields, limit);
                    featureTable.finish();
                    results.add(result);
                }
            }
            return true;
        });
        addFunction("cutOff", (data, results, context, option) -> {
            if (CollectionUtils.isNotEmpty(data)) {
                for (DataResult item : data) {
                    DataResult result = new DataResult();
                    FeatureTable featureTable = new FeatureTable(item.getFeatureTable().getName(), item.getFields(), ArrowAllocator.getAllocator());
                    result.setFeatureTable(featureTable);
                    result.setDataTypes(item.getDataTypes());
                    int limit = CommonUtils.getField(option, "maxReservation", DEFAULT_MAX_RESERVATION);
                    result.copyDataResult(item, 0, limit);
                    featureTable.finish();
                    results.add(result);
                }
            }
            return true;
        });
        addFunction("updateField", (data, results, context, option) -> {
            if (CollectionUtils.isNotEmpty(data)) {
                for (DataResult item : data) {
                    DataResult result = new DataResult();
                    List<Field> fields = Lists.newArrayList();
                    List<DataTypeEnum> dataTypes = item.getDataTypes();
                    Set<String> fieldSet = Sets.newHashSet();
                    List<String> inputFields = getOptionFields("input", option);
                    List<String> outputFields = getOptionFields("output", option);
                    List<Object> outputTypes = getOptionFields("outputType", option);
                    for (Field field : item.getFields()) {
                        fieldSet.add(field.getName());
                        fields.add(field);
                    }
                    Assert.isTrue(CollectionUtils.isNotEmpty(inputFields) && CollectionUtils.isNotEmpty(outputFields), "input and output must not empty");
                    for (String field : inputFields) {
                        Assert.isTrue(fieldSet.contains(field), "input field must in dataResult schema");
                    }
                    if (CollectionUtils.isEmpty(outputTypes)) {
                        for (String field : outputFields) {
                            Assert.isTrue(fieldSet.contains(field), "output field must in dataResult schema when no set type!");
                        }
                    } else {
                        Assert.isTrue(outputTypes.size() == outputFields.size(), "output field and type must has same size");
                        for (int i = 0; i < outputFields.size(); ++i) {
                            fields.add(getField(outputFields.get(i), outputTypes.get(i)));
                            dataTypes.add(getType(outputTypes.get(i)));
                        }
                    }
                    FeatureTable featureTable = new FeatureTable(item.getFeatureTable().getName(), fields, ArrowAllocator.getAllocator());
                    result.setFeatureTable(featureTable);
                    result.setDataTypes(dataTypes);

                    result.updateDataResult(item, inputFields, outputFields, getUpdateOperator(option), option);
                    featureTable.finish();
                    results.add(result);
                }
            }
            return true;
        });
    }

    public abstract void initFunctions();

    public void addFunction(String name, TransformFunction function) {
        transformFunctions.put(name, function);
    }

    public void registerMergeOperator(String name, MergeOperator operator) {
        mergeOperators.put(name, operator);
    }

    public void registerUpdateOperator(String name, UpdateOperator operator) {
        updateOperators.put(name, operator);
    }

    @SuppressWarnings("rawtypes")
    private <T> List<T> getOptionFields(String name, Map<String, Object> option) {
        List<T> dupFields = Lists.newArrayList();
        if (MapUtils.isNotEmpty(option)) {
            Object dupFieldValue = option.get(name);
            if (dupFieldValue == null) return dupFields;
            if (dupFieldValue instanceof Map) {
                dupFields.addAll(((Map) dupFieldValue).values());
            } else if (dupFieldValue instanceof Collection) {
                dupFields.addAll((Collection) dupFieldValue);
            }
        }
        return dupFields;
    }

    private <T> T getOperatorOrFunction(@NonNull Map<String, T> beans, @NonNull String name, Class<?> cls) {
        T bean = beans.get(name);
        if (bean == null) {
            return serviceRegister.getUDFBean(name, cls);
        }
        return bean;
    }

    public Map<String, MergeOperator> getMergeOperators(Map<String, Object> option) {
        Map<String, MergeOperator> mergeOperatorMap = Maps.newHashMap();
        Map<String, String> mergeFieldOperator = CommonUtils.getField(option, "mergeOperator", Map.of());
        if (MapUtils.isNotEmpty(mergeFieldOperator)) {
            for (Map.Entry<String, String> entry : mergeFieldOperator.entrySet()) {
                MergeOperator operator = getOperatorOrFunction(mergeOperators, entry.getValue(), MergeOperator.class);
                if (operator == null) {
                    log.error("merge operator no found config fail col: {}, operator: {}", entry.getKey(), entry.getValue());
                    continue;
                }
                mergeOperatorMap.put(entry.getKey(), operator);
            }
        }
        return mergeOperatorMap;
    }

    public UpdateOperator getUpdateOperator(Map<String, Object> option) {
        String updateFieldOperator = CommonUtils.getField(option, "updateOperator", "");
        return getOperatorOrFunction(updateOperators, updateFieldOperator, UpdateOperator.class);
    }

    public CompletableFuture<List<DataResult>> executeTransform(CompletableFuture<List<DataResult>> future,
                                                                List<TransformConfig> transforms,
                                                                Map<String, Object> args,
                                                                DataContext context) {
        if (future == null || CollectionUtils.isEmpty(transforms)) return null;
        for (TransformConfig item : transforms) {
            TransformFunction function = getOperatorOrFunction(transformFunctions, item.getName(), TransformFunction.class);
            if (function == null) {
                log.error("the service：{} function: {} is not exist!", name, item.getName());
                continue;
            }
            Map<String, Object> option = Maps.newHashMap();
            if (MapUtils.isNotEmpty(args)) {
                option.putAll(args);
            }
            if (MapUtils.isNotEmpty(item.getOption())) {
                option.putAll(item.getOption());
            }
            future = future.thenApplyAsync(dataResults -> {
                List<DataResult> resultList = Lists.newArrayList();
                if (CollectionUtils.isEmpty(dataResults)) {
                    log.error("the service：{} function: {} input is empty!", name, item.getName());
                    return resultList;
                }
                if (!function.transform(dataResults, resultList, context, option)) {
                    log.error("the service：{} function: {} execute fail!", name, item.getName());
                }
                return resultList;
            }, taskPool);
        }
        return future;
    }

}
