/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog2.indexer.ranges;

import com.lordofthejars.nosqlunit.annotation.UsingDataSet;
import com.lordofthejars.nosqlunit.core.LoadStrategyEnum;
import com.lordofthejars.nosqlunit.mongodb.InMemoryMongoDb;
import org.graylog2.database.MongoConnectionRule;
import org.graylog2.database.NotFoundException;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.SortedSet;

import static com.lordofthejars.nosqlunit.mongodb.InMemoryMongoDb.InMemoryMongoRuleBuilder.newInMemoryMongoDbRule;
import static org.assertj.core.api.Assertions.assertThat;

@RunWith(MockitoJUnitRunner.class)
public class MongoIndexRangeServiceTest {
    @ClassRule
    public static final InMemoryMongoDb IN_MEMORY_MONGO_DB = newInMemoryMongoDbRule().build();
    private static final DateTime EPOCH = new DateTime(0L, DateTimeZone.UTC);

    @Rule
    public MongoConnectionRule mongoRule = MongoConnectionRule.build("test");

    private MongoIndexRangeService indexRangeService;

    @Before
    public void setUp() throws Exception {
        indexRangeService = new MongoIndexRangeService(mongoRule.getMongoConnection());
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void testGetExistingIndexRange() throws Exception {
        final IndexRange indexRange = indexRangeService.get("graylog_0");
        final DateTime end = new DateTime(2015, 1, 1, 0, 0, 0, 0, DateTimeZone.UTC);
        final IndexRange expected = IndexRange.create("graylog_0", EPOCH, end, end, 0);
        assertThat(indexRange).isEqualTo(expected);
    }

    @Test(expected = NotFoundException.class)
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void testGetNonExistingIndexRange() throws Exception {
        indexRangeService.get("does-not-exist");
    }

    @Test(expected = NotFoundException.class)
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void testGetInvalidIndexRange() throws Exception {
        indexRangeService.get("invalid");
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void testGetIncompleteIndexRange() throws Exception {
        final IndexRange indexRange = indexRangeService.get("graylog_99");
        final DateTime end = new DateTime(2015, 1, 1, 0, 0, 0, 0, DateTimeZone.UTC);
        final IndexRange expected = IndexRange.create("graylog_99", EPOCH, end, EPOCH, 0);
        assertThat(indexRange).isEqualTo(expected);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testFind() throws Exception {
        indexRangeService.find(new DateTime(0L, DateTimeZone.UTC), DateTime.now(DateTimeZone.UTC));
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void testFindAll() throws Exception {
        final SortedSet<IndexRange> indexRanges = indexRangeService.findAll();

        final DateTime end1 = new DateTime(2015, 1, 2, 0, 0, 0, 0, DateTimeZone.UTC);
        final DateTime end2 = new DateTime(2015, 1, 3, 0, 0, 0, 0, DateTimeZone.UTC);
        final DateTime end99 = new DateTime(2015, 1, 1, 0, 0, 0, 0, DateTimeZone.UTC);
        assertThat(indexRanges).containsExactly(
                IndexRange.create("graylog_99", EPOCH, end99, EPOCH, 0),
                IndexRange.create("graylog_1", EPOCH, end1, end1, 1),
                IndexRange.create("graylog_2", EPOCH, end2, end2, 2)
        );
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testSave() throws Exception {
        indexRangeService.save((IndexRange) null);
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testCalculateRange() throws Exception {
        indexRangeService.calculateRange("graylog_0");
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void testMarkAsMigratedIsIdempotent() throws Exception {
        assertThat(indexRangeService.isMigrated("graylog_0")).isTrue();

        assertThat(indexRangeService.markAsMigrated("graylog_0")).isTrue();

        assertThat(indexRangeService.isMigrated("graylog_0")).isTrue();
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void testMarkAsMigrated() throws Exception {
        assertThat(indexRangeService.isMigrated("graylog_1")).isFalse();
        assertThat(indexRangeService.isMigrated("graylog_2")).isFalse();

        assertThat(indexRangeService.markAsMigrated("graylog_1")).isTrue();

        assertThat(indexRangeService.isMigrated("graylog_1")).isTrue();
        assertThat(indexRangeService.isMigrated("graylog_2")).isFalse();
    }

    @Test
    @UsingDataSet(loadStrategy = LoadStrategyEnum.CLEAN_INSERT)
    public void testIsMigrated() throws Exception {
        assertThat(indexRangeService.isMigrated("graylog_0")).isTrue();
        assertThat(indexRangeService.isMigrated("graylog_1")).isFalse();
        assertThat(indexRangeService.isMigrated("graylog_2")).isFalse();
    }
}