/**
 * This file is part of Graylog2.
 *
 * Graylog2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog2.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog2.buffers.processors.fakestreams;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.graylog2.plugin.outputs.MessageOutput;
import org.graylog2.streams.StreamImpl;

import java.util.List;

public class FakeStream extends StreamImpl {
    private List<MessageOutput> outputs = Lists.newArrayList();

    public FakeStream(String title) {
        super(Maps.<String, Object>newHashMap());
    }

    public void addOutput(MessageOutput output) {
        outputs.add(output);
    }
}