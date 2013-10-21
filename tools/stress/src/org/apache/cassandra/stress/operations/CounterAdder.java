/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.stress.operations;

import com.yammer.metrics.core.TimerContext;
import org.apache.cassandra.stress.Session;
import org.apache.cassandra.stress.StressMetrics;
import org.apache.cassandra.stress.util.CassandraClient;
import org.apache.cassandra.stress.util.Operation;
import org.apache.cassandra.db.ColumnFamilyType;
import org.apache.cassandra.thrift.*;
import org.apache.cassandra.utils.ByteBufferUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CounterAdder extends Operation
{
    public CounterAdder(Settings settings, int index)
    {
        super(settings, index);
    }

    public void run(final CassandraClient client) throws IOException
    {
        List<CounterColumn> columns = new ArrayList<CounterColumn>();
        for (int i = 0; i < settings.columnsPerKey; i++)
            columns.add(new CounterColumn(getColumnName(i), 1L));

        Map<String, List<Mutation>> row;
        if (settings.useSuperColumns)
        {
            List<Mutation> mutations = new ArrayList<>();
            for (ColumnParent parent : settings.columnParents)
            {
                CounterSuperColumn csc = new CounterSuperColumn(ByteBuffer.wrap(parent.getSuper_column()), columns);
                ColumnOrSuperColumn cosc = new ColumnOrSuperColumn().setCounter_super_column(csc);
                mutations.add(new Mutation().setColumn_or_supercolumn(cosc));
            }
            row = Collections.singletonMap("SuperCounter1", mutations);
        }
        else
        {
            List<Mutation> mutations = new ArrayList<>(columns.size());
            for (CounterColumn c : columns)
            {
                ColumnOrSuperColumn cosc = new ColumnOrSuperColumn().setCounter_column(c);
                mutations.add(new Mutation().setColumn_or_supercolumn(cosc));
            }
            row = Collections.singletonMap("Counter1", mutations);
        }

        final ByteBuffer key = getKey();
        final Map<ByteBuffer, Map<String, List<Mutation>>> record = Collections.singletonMap(key, row);

        timeWithRetry(new RunOp()
        {
            @Override
            public boolean run() throws Exception
            {
                client.batch_mutate(record, settings.consistencyLevel);
                return true;
            }

            @Override
            public String key()
            {
                return new String(key.array());
            }

            @Override
            public int keyCount()
            {
                return 1;
            }
        });
    }

}
