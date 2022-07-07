#
# Copyright 2022 DMetaSoul
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

import yaml
from .node import PipelineNode
from ..jobs.item_model import item_attr_run
from ..jobs.user_model import user_attr_run
from ..jobs.common import push_mongo, push_milvus
from ..utils import start_logging

class Attribute2INode(PipelineNode):
    def __call__(self, **payload) -> dict:
        node_conf = payload['conf']        
        self.match_core(node_conf)
        return payload

    @staticmethod
    def dict2str(d, kv_sep=':', item_sep=','):
        kv_list = []
        for k, v in d.items():
            kv_list.append(f"{k}{kv_sep}{v}")
        return item_sep.join(kv_list)
    
    def match_core(self, conf) -> None:
        # spark conf
        spark_conf = conf['spark']['session_confs']
        app_name = spark_conf['app_name']
        del spark_conf['app_name']
        spark_conf_str = self.dict2str(spark_conf)

        # data input&output conf
        data_conf = conf['data']
        item_data = data_conf['input']['item_data']
        action_data = data_conf['input']['action_data']
        item_attr_data = data_conf['dump']['item_attr_data']
        user_attr_data = data_conf['dump']['user_attr_data']
        item_rtag_data = data_conf['dump']['item_rtag_data']

        # item embed and push
        item_conf = conf['jobs']['item_attr']
        # attr
        a = item_conf['run']
        if a['status']:
            print("* item attr")
            item_attr_run(item_data, action_data, item_attr_data, item_rtag_data, 
                a['scene_id'], a['action_type'], a['action_value_min'], 
                a['action_value_max'], a['tag_max_len'], a['write_mode'], 
                job_name=f"{app_name}-item-attr", spark_conf=spark_conf_str)
        # push item attr
        a = item_conf['push_attr']
        if a['status']:
            print("* push item attr")
            push_mongo(a['mongo_uri'], a['mongo_database'], a['mongo_collection'], item_attr_data, 
                a['fields'], a['index_fields'], a['write_mode'], job_name=f"{app_name}-push-item-attr")
        # push item rtag
        a = item_conf['push_rtag']
        if a['status']:
            print("* push item rtag")
            push_mongo(a['mongo_uri'], a['mongo_database'], a['mongo_collection'], item_rtag_data, 
                a['fields'], a['index_fields'], a['write_mode'], job_name=f"{app_name}-push-item-rtag")

        # user attr and push
        user_conf = conf['jobs']['user_attr']
        # user attr
        a = user_conf['run']
        if a['status']:
            print("* user attr")
            user_attr_run(action_data, item_data, user_attr_data, a['scene_id'],
                a['action_type'], a['action_value_min'], a['action_value_max'],
                a['action_sortby_key'], a['action_max_len'], a['user_tags_topk'], 
                a['write_mode'], job_name=f"{app_name}-user-attr", spark_conf=spark_conf_str)
        # push user attr
        a = user_conf['push_attr']
        if a['status']:
            print("* push user attr")
            push_mongo(a['mongo_uri'], a['mongo_database'], a['mongo_collection'], user_attr_data,
                a['fields'], a['index_fields'], a['write_mode'], job_name=f"{app_name}-push-user-attr")