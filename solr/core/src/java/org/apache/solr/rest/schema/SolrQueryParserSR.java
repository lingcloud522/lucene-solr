package org.apache.solr.rest.schema;
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.google.inject.Inject;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.request.SolrQueryRequestDecoder;
import org.apache.solr.schema.IndexSchema;
import org.restlet.representation.Representation;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class responds to requests at /solr/(corename)/schema/solrqueryparser
 */
public class SolrQueryParserSR extends BaseSchemaResource implements SolrQueryParserResource {
  private static final Logger log = LoggerFactory.getLogger(SolrQueryParserSR.class);

  @Inject
  public SolrQueryParserSR(SolrQueryRequestDecoder requestDecoder) {
    super(requestDecoder);
  }

  @Override
  public void doInit() throws ResourceException {
    super.doInit();
  }

  @Override
  public Representation get() {
    try {
      SimpleOrderedMap<Object> props = new SimpleOrderedMap<Object>();
      props.add(IndexSchema.DEFAULT_OPERATOR, getSchema().getQueryParserDefaultOperator());
      getSolrResponse().add(IndexSchema.SOLR_QUERY_PARSER, props);
    } catch (Exception e) {
      getSolrResponse().setException(e);
    }
    handlePostExecution(log);

    return new SolrOutputRepresentation();
  }
}
