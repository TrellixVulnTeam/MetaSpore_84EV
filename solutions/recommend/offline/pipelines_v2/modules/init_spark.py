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

from logging import Logger

import attrs
from typing import Optional, Dict
import metaspore as ms
import subprocess
from pyspark.sql import SparkSession

@attrs.frozen
class InitSparkConfig:
    session_confs = attrs.field(validator=attrs.validators.instance_of(Dict))
    extended_confs = attrs.field(validator=attrs.validators.instance_of(Dict))
    pyzip = attrs.field(default=None, validator=attrs.validators.instance_of(Dict))

class InitSparkModule():
    def __init__(self, conf: InitSparkConfig, logger: Logger):
        self.conf = conf
        self.logger = logger
    
    def run(self) -> SparkSession:
        session_confs = self.conf.session_confs

        extended_confs = self.conf.extended_confs or {}
        
        if self.conf.pyzip:
            cwd_path = self.conf.pyzip['cwd_path']
            zip_file_path = self.conf.pyzip['zip_file_path']
            subprocess.run(['zip', '-r', zip_file_path, 'python'], cwd=cwd_path)
            extended_confs['spark.submit.pyFiles'] = 'python.zip'
        
        spark = ms.spark.get_session(local=session_confs['local'],
                                     app_name=session_confs['app_name'] or 'metaspore',
                                     batch_size=session_confs['batch_size'] or 100,
                                     worker_count=session_confs['worker_count'] or 1,
                                     server_count=session_confs['server_count'] or 1,
                                     worker_cpu=session_confs['worker_cpu'] or 1,
                                     server_cpu=session_confs['server_cpu'] or 1,
                                     worker_memory=session_confs['worker_memory'] or '5G',
                                     server_memory=session_confs['server_memory'] or '5G',
                                     coordinator_memory=session_confs['coordinator_memory'] or '5G',
                                     spark_confs=extended_confs)
        sc = spark.sparkContext
        self.logger.info('Spark init, version: {}, applicationId: {}, uiWebUrl: {}'\
                     .format( sc.version, sc.applicationId, sc.uiWebUrl))
        
        return spark, session_confs['worker_count'], session_confs['server_count']
    