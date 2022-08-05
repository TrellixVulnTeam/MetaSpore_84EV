import metaspore as ms
import logging
import attrs
import logging

from typing import Dict, Tuple, Optional
from pyspark.sql import DataFrame
from pyspark.mllib.evaluation import RankingMetrics
from pyspark.sql import functions as F
from ..utils import get_class

logger = logging.getLogger(__name__)

@attrs.frozen
class TwoTowersRetrievalConfig:
    user_module_class = attrs.field(validator=attrs.validators.instance_of(Dict))
    item_module_class = attrs.field(validator=attrs.validators.instance_of(Dict))
    similarity_module_class = attrs.field(validator=attrs.validators.instance_of(Dict))
    two_tower_retrieval_module_class = attrs.field(validator=attrs.validators.instance_of(Dict))
    two_tower_agent_class = attrs.field(validator=attrs.validators.instance_of(Dict))
    two_tower_estimator_class = attrs.field(validator=attrs.validators.instance_of(Dict))
    model_params = attrs.field(validator=attrs.validators.instance_of(Dict))
    estimator_params = attrs.field(validator=attrs.validators.instance_of(Dict))
    
class TwoTowersRetrievalModule():
    def __init__(self, conf: TwoTowersRetrievalConfig):
        self.conf = conf
        self.model = None
        self.metric_position_k = 20
    
    def _construct_net_with_params(self, module_type, module_class, model_params):
        if module_type in ['user', 'item']:
            return  module_class(column_name_path = model_params['user_column_name'], \
                                 combine_schema_path = model_params['user_combine_schema'], \
                                 embedding_dim = model_params['vector_embedding_size'], \
                                 sparse_init_var = model_params['sparse_init_var'], \
                                 ftrl_l1 = model_params['ftrl_l1_regularization'], \
                                 ftrl_l2 = model_params['ftrl_l2_regularization'], \
                                 ftrl_alpha = model_params['ftrl_learning_rate'], \
                                 ftrl_beta = model_params['ftrl_smothing_rate'], \
                                 dnn_hidden_units = model_params['dnn_hidden_units'], \
                                 dnn_hidden_activations = model_params['dnn_hidden_activations'])
        elif module_type in ['sim']:
            return module_class(model_params['tau'])
        else:
            return None
    
    def train(self, train_dataset, item_dataset, worker_count, server_count):
        # init user module, item module, similarity module
        user_module_class = get_class(**self.conf.user_module_class)
        user_module = self._construct_net_with_params('user', user_module_class, self.conf.model_params)
        item_module_class = get_class(**self.conf.item_module_class)
        item_module = self._construct_net_with_params('item', item_module_class, self.conf.model_params)
        similarity_class = get_class(**self.conf.similarity_module_class)
        similarity_module = self._construct_net_with_params('sim', similarity_class, self.conf.model_params)
        # init two tower module
        two_tower_retrieval_module_class = get_class(**self.conf.two_tower_retrieval_module_class)
        two_tower_retrieval_module = two_tower_retrieval_module_class(user_module, item_module, similarity_module)
        # init agent
        two_tower_agent_class = get_class(**self.conf.two_tower_agent_class)
        ## init estimator
        two_tower_estimator_class = get_class(**self.conf.two_tower_estimator_class)
        two_tower_estimator = two_tower_estimator_class(
            module = two_tower_retrieval_module,
            item_dataset = item_dataset,
            agent_class = two_tower_agent_class,
            worker_count = worker_count,
            server_count = server_count,
            **{**self.conf.model_params, **self.conf.estimator_params}
        )
        # model train
        two_tower_estimator.updater = ms.AdamTensorUpdater(self.conf.model_params['adam_learning_rate'])
        self.model = two_tower_estimator.fit(train_dataset)
        
        logger.info('DeepCTR - training: done')
    
    def evaluate(self, test_result, user_id_column='user_id', item_id_column='item_id'):
        logger.info('test sample:')
        test_result.select(user_id_column, (F.posexplode('rec_info').alias('pos', 'rec_info'))).show(10)
        
        prediction_label_rdd = test_result.rdd.map(lambda x:(\
                                                [xx.name for xx in x.rec_info] if x.rec_info is not None else [], \
                                                [getattr(x, item_id_column)]))

        metrics = RankingMetrics(prediction_label_rdd)
        metric_dict = {}
        metric_dict['Precision@{}'.format(self.metric_position_k)] = metrics.precisionAt(self.metric_position_k)
        metric_dict['Recall@{}'.format(self.metric_position_k)] = metrics.recallAt(self.metric_position_k)
        metric_dict['MAP@{}'.format(self.metric_position_k)] = metrics.meanAveragePrecisionAt(self.metric_position_k)
        metric_dict['NDCG@{}'.format(self.metric_position_k)] = metrics.ndcgAt(self.metric_position_k)
        logger.info('Metrics: {}'.format(metric_dict))
        logger.info('TwoTwoers - evaluation: done')
        return metric_dict
        
    
    def run(self, train_dataset, test_dataset, item_dataset, worker_count, server_count,
            user_id_column='user_id', item_id_column='item_id', label_column='label') -> Dict[str, float]:
        if not isinstance(train_dataset, DataFrame):
            raise ValueError("Type of train_dataset must be DataFrame.")
        if not isinstance(test_dataset, DataFrame):
            raise ValueError("Type of test_dataset must be DataFrame.")
        
        # 1. create estimator and fit model
        self.train(train_dataset, item_dataset, worker_count, server_count)
        
        # 2. transform test data using self.model
        test_result = self.model.transform(test_dataset)
        logger.info('TwoTowers - inference: done')
        
        # 3. get metric dictionary (metric name -> metric value)
        metric_dict = self.evaluate(test_result, item_id_column=item_id_column)
        logger.info('TwoTowers evaluation metrics: {}'.format(metric_dict))
        
        return metric_dict