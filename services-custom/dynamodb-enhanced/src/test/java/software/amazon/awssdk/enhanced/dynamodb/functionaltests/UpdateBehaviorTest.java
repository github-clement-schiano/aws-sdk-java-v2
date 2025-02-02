package software.amazon.awssdk.enhanced.dynamodb.functionaltests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Collections;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.extensions.AutoGeneratedTimestampRecordExtension;
import software.amazon.awssdk.enhanced.dynamodb.functionaltests.models.CompositeRecord;
import software.amazon.awssdk.enhanced.dynamodb.functionaltests.models.FlattenRecord;
import software.amazon.awssdk.enhanced.dynamodb.functionaltests.models.NestedRecordWithUpdateBehavior;
import software.amazon.awssdk.enhanced.dynamodb.functionaltests.models.RecordWithUpdateBehaviors;
import software.amazon.awssdk.enhanced.dynamodb.internal.client.ExtensionResolver;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

public class UpdateBehaviorTest extends LocalDynamoDbSyncTestBase {
    private static final Instant INSTANT_1 = Instant.parse("2020-05-03T10:00:00Z");
    private static final Instant INSTANT_2 = Instant.parse("2020-05-03T10:05:00Z");
    private static final Instant FAR_FUTURE_INSTANT = Instant.parse("9999-05-03T10:05:00Z");
    private static final String TEST_BEHAVIOUR_ATTRIBUTE = "testBehaviourAttribute";
    private static final String TEST_ATTRIBUTE = "testAttribute";

    private static final TableSchema<RecordWithUpdateBehaviors> TABLE_SCHEMA =
            TableSchema.fromClass(RecordWithUpdateBehaviors.class);
    
    private static final TableSchema<FlattenRecord> TABLE_SCHEMA_FLATTEN_RECORD =
        TableSchema.fromClass(FlattenRecord.class);

    private final DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
            .dynamoDbClient(getDynamoDbClient()).extensions(
            Stream.concat(ExtensionResolver.defaultExtensions().stream(),
                          Stream.of(AutoGeneratedTimestampRecordExtension.create())).collect(Collectors.toList()))
            .build();

    private final DynamoDbTable<RecordWithUpdateBehaviors> mappedTable =
            enhancedClient.table(getConcreteTableName("table-name"), TABLE_SCHEMA);
    
    private final DynamoDbTable<FlattenRecord> flattenedMappedTable =
        enhancedClient.table(getConcreteTableName("table-name"), TABLE_SCHEMA_FLATTEN_RECORD);

    @Before
    public void createTable() {
        mappedTable.createTable(r -> r.provisionedThroughput(getDefaultProvisionedThroughput()));
    }

    @After
    public void deleteTable() {
        getDynamoDbClient().deleteTable(r -> r.tableName(getConcreteTableName("table-name")));
    }

    @Test
    public void updateBehaviors_firstUpdate() {
        Instant currentTime = Instant.now();
        RecordWithUpdateBehaviors record = new RecordWithUpdateBehaviors();
        record.setId("id123");
        record.setCreatedOn(INSTANT_1);
        record.setLastUpdatedOn(INSTANT_2);
        mappedTable.updateItem(record);

        RecordWithUpdateBehaviors persistedRecord = mappedTable.getItem(record);

        assertThat(persistedRecord.getVersion()).isEqualTo(1L);

        assertThat(persistedRecord.getCreatedOn()).isEqualTo(INSTANT_1);
        assertThat(persistedRecord.getLastUpdatedOn()).isEqualTo(INSTANT_2);
        assertThat(persistedRecord.getLastAutoUpdatedOn()).isAfterOrEqualTo(currentTime);
        assertThat(persistedRecord.getFormattedLastAutoUpdatedOn().getEpochSecond())
            .isGreaterThanOrEqualTo(currentTime.getEpochSecond());

        assertThat(persistedRecord.getLastAutoUpdatedOnMillis().getEpochSecond()).isGreaterThanOrEqualTo(currentTime.getEpochSecond());
        assertThat(persistedRecord.getCreatedAutoUpdateOn()).isAfterOrEqualTo(currentTime);
    }

    @Test
    public void updateBehaviors_secondUpdate() {
        Instant beforeUpdateInstant = Instant.now();
        RecordWithUpdateBehaviors record = new RecordWithUpdateBehaviors();
        record.setId("id123");
        record.setCreatedOn(INSTANT_1);
        record.setLastUpdatedOn(INSTANT_2);
        mappedTable.updateItem(record);
        RecordWithUpdateBehaviors persistedRecord = mappedTable.getItem(record);

        assertThat(persistedRecord.getVersion()).isEqualTo(1L);
        Instant firstUpdatedTime = persistedRecord.getLastAutoUpdatedOn();
        Instant createdAutoUpdateOn = persistedRecord.getCreatedAutoUpdateOn();
        assertThat(firstUpdatedTime).isAfterOrEqualTo(beforeUpdateInstant);
        assertThat(persistedRecord.getFormattedLastAutoUpdatedOn().getEpochSecond())
            .isGreaterThanOrEqualTo(beforeUpdateInstant.getEpochSecond());

        record.setVersion(1L);
        record.setCreatedOn(INSTANT_2);
        record.setLastUpdatedOn(INSTANT_2);
        mappedTable.updateItem(record);

        persistedRecord = mappedTable.getItem(record);
        assertThat(persistedRecord.getVersion()).isEqualTo(2L);
        assertThat(persistedRecord.getCreatedOn()).isEqualTo(INSTANT_1);
        assertThat(persistedRecord.getLastUpdatedOn()).isEqualTo(INSTANT_2);

        Instant secondUpdatedTime = persistedRecord.getLastAutoUpdatedOn();
        assertThat(secondUpdatedTime).isAfterOrEqualTo(firstUpdatedTime);
        assertThat(persistedRecord.getCreatedAutoUpdateOn()).isEqualTo(createdAutoUpdateOn);
    }

    @Test
    public void updateBehaviors_removal() {
        RecordWithUpdateBehaviors record = new RecordWithUpdateBehaviors();
        record.setId("id123");
        record.setCreatedOn(INSTANT_1);
        record.setLastUpdatedOn(INSTANT_2);
        record.setLastAutoUpdatedOn(FAR_FUTURE_INSTANT);
        mappedTable.updateItem(record);
        RecordWithUpdateBehaviors persistedRecord = mappedTable.getItem(record);
        Instant createdAutoUpdateOn = persistedRecord.getCreatedAutoUpdateOn();
        assertThat(persistedRecord.getLastAutoUpdatedOn()).isBefore(FAR_FUTURE_INSTANT);

        record.setVersion(1L);
        record.setCreatedOn(null);
        record.setLastUpdatedOn(null);
        record.setLastAutoUpdatedOn(null);
        mappedTable.updateItem(record);

        persistedRecord = mappedTable.getItem(record);
        assertThat(persistedRecord.getCreatedOn()).isNull();
        assertThat(persistedRecord.getLastUpdatedOn()).isNull();
        assertThat(persistedRecord.getLastAutoUpdatedOn()).isNotNull();
        assertThat(persistedRecord.getCreatedAutoUpdateOn()).isEqualTo(createdAutoUpdateOn);
    }

    @Test
    public void updateBehaviors_transactWriteItems_secondUpdate() {
        RecordWithUpdateBehaviors record = new RecordWithUpdateBehaviors();
        record.setId("id123");
        record.setCreatedOn(INSTANT_1);
        record.setLastUpdatedOn(INSTANT_2);
        record.setLastAutoUpdatedOn(INSTANT_2);
        RecordWithUpdateBehaviors firstUpdatedRecord = mappedTable.updateItem(record);

        record.setVersion(1L);
        record.setCreatedOn(INSTANT_2);
        record.setLastUpdatedOn(INSTANT_2);
        record.setLastAutoUpdatedOn(INSTANT_2);
        enhancedClient.transactWriteItems(r -> r.addUpdateItem(mappedTable, record));

        RecordWithUpdateBehaviors persistedRecord = mappedTable.getItem(record);
        assertThat(persistedRecord.getCreatedOn()).isEqualTo(INSTANT_1);
        assertThat(persistedRecord.getLastUpdatedOn()).isEqualTo(INSTANT_2);
        assertThat(persistedRecord.getLastAutoUpdatedOn()).isAfterOrEqualTo(INSTANT_2);
        assertThat(persistedRecord.getCreatedAutoUpdateOn()).isEqualTo(firstUpdatedRecord.getCreatedAutoUpdateOn());
    }

    @Test
    public void when_updatingNestedObjectWithSingleLevel_existingInformationIsPreserved_ignoreNulls() {

        NestedRecordWithUpdateBehavior nestedRecord = createNestedWithDefaults("id456", 5L);

        RecordWithUpdateBehaviors record = new RecordWithUpdateBehaviors();
        record.setId("id123");
        record.setNestedRecord(nestedRecord);

        mappedTable.putItem(record);

        NestedRecordWithUpdateBehavior updatedNestedRecord = new NestedRecordWithUpdateBehavior();
        long updatedNestedCounter = 10L;
        updatedNestedRecord.setNestedCounter(updatedNestedCounter);
        updatedNestedRecord.setAttribute(TEST_ATTRIBUTE);

        RecordWithUpdateBehaviors update_record = new RecordWithUpdateBehaviors();
        update_record.setId("id123");
        update_record.setVersion(1L);
        update_record.setNestedRecord(updatedNestedRecord);
        
        mappedTable.updateItem(r -> r.item(update_record).ignoreNulls(true));

        RecordWithUpdateBehaviors persistedRecord = mappedTable.getItem(r -> r.key(k -> k.partitionValue("id123")));

        verifySingleLevelNestingTargetedUpdateBehavior(persistedRecord, updatedNestedCounter, TEST_ATTRIBUTE);
    }

    @Test
    public void when_updatingNestedObjectToEmptyWithSingleLevel_existingInformationIsPreserved_ignoreNulls() {

        NestedRecordWithUpdateBehavior nestedRecord = createNestedWithDefaults("id456", 5L);
        nestedRecord.setAttribute(TEST_ATTRIBUTE);

        RecordWithUpdateBehaviors record = new RecordWithUpdateBehaviors();
        record.setId("id123");
        record.setNestedRecord(nestedRecord);

        mappedTable.putItem(record);

        NestedRecordWithUpdateBehavior updatedNestedRecord = new NestedRecordWithUpdateBehavior();

        RecordWithUpdateBehaviors update_record = new RecordWithUpdateBehaviors();
        update_record.setId("id123");
        update_record.setVersion(1L);
        update_record.setNestedRecord(updatedNestedRecord);

        mappedTable.updateItem(r -> r.item(update_record).ignoreNulls(true));

        RecordWithUpdateBehaviors persistedRecord = mappedTable.getItem(r -> r.key(k -> k.partitionValue("id123")));
        assertThat(persistedRecord.getNestedRecord()).isNull();
    }

    private NestedRecordWithUpdateBehavior createNestedWithDefaults(String id, Long counter) {
        NestedRecordWithUpdateBehavior nestedRecordWithDefaults = new NestedRecordWithUpdateBehavior();
        nestedRecordWithDefaults.setId(id);
        nestedRecordWithDefaults.setNestedCounter(counter);
        nestedRecordWithDefaults.setNestedUpdateBehaviorAttribute(TEST_BEHAVIOUR_ATTRIBUTE);
        nestedRecordWithDefaults.setNestedTimeAttribute(INSTANT_1);

        return nestedRecordWithDefaults;
    }

    private void verifyMultipleLevelNestingTargetedUpdateBehavior(RecordWithUpdateBehaviors persistedRecord,
                                                                  long updatedOuterNestedCounter,
                                                                  long updatedInnerNestedCounter) {
        assertThat(persistedRecord.getNestedRecord()).isNotNull();
        assertThat(persistedRecord.getNestedRecord().getNestedRecord()).isNotNull();

        assertThat(persistedRecord.getNestedRecord().getNestedCounter()).isEqualTo(updatedOuterNestedCounter);
        assertThat(persistedRecord.getNestedRecord().getNestedRecord()).isNotNull();
        assertThat(persistedRecord.getNestedRecord().getNestedRecord().getNestedCounter()).isEqualTo(updatedInnerNestedCounter);
        assertThat(persistedRecord.getNestedRecord().getNestedRecord().getNestedUpdateBehaviorAttribute()).isEqualTo(
            TEST_BEHAVIOUR_ATTRIBUTE);
        assertThat(persistedRecord.getNestedRecord().getNestedRecord().getAttribute()).isEqualTo(
            TEST_ATTRIBUTE);
        assertThat(persistedRecord.getNestedRecord().getNestedRecord().getNestedTimeAttribute()).isEqualTo(INSTANT_1);
    }

    private void verifySingleLevelNestingTargetedUpdateBehavior(RecordWithUpdateBehaviors persistedRecord,
                                                                  long updatedNestedCounter, String testAttribute) {
        assertThat(persistedRecord.getNestedRecord()).isNotNull();
        assertThat(persistedRecord.getNestedRecord().getNestedCounter()).isEqualTo(updatedNestedCounter);
        assertThat(persistedRecord.getNestedRecord().getNestedUpdateBehaviorAttribute()).isEqualTo(TEST_BEHAVIOUR_ATTRIBUTE);
        assertThat(persistedRecord.getNestedRecord().getNestedTimeAttribute()).isEqualTo(INSTANT_1);
        assertThat(persistedRecord.getNestedRecord().getAttribute()).isEqualTo(testAttribute);
    }

    @Test
    public void when_updatingNestedObjectWithMultipleLevels_existingInformationIsPreserved() {

        NestedRecordWithUpdateBehavior nestedRecord1 = createNestedWithDefaults("id789", 50L);

        NestedRecordWithUpdateBehavior nestedRecord2 = createNestedWithDefaults("id456", 0L);
        nestedRecord2.setNestedRecord(nestedRecord1);

        RecordWithUpdateBehaviors record = new RecordWithUpdateBehaviors();
        record.setId("id123");
        record.setNestedRecord(nestedRecord2);

        mappedTable.putItem(record);

        NestedRecordWithUpdateBehavior updatedNestedRecord2 = new NestedRecordWithUpdateBehavior();
        long innerNestedCounter = 100L;
        updatedNestedRecord2.setNestedCounter(innerNestedCounter);
        updatedNestedRecord2.setAttribute(TEST_ATTRIBUTE);

        NestedRecordWithUpdateBehavior updatedNestedRecord1 = new NestedRecordWithUpdateBehavior();
        updatedNestedRecord1.setNestedRecord(updatedNestedRecord2);
        long outerNestedCounter = 200L;
        updatedNestedRecord1.setNestedCounter(outerNestedCounter);

        RecordWithUpdateBehaviors update_record = new RecordWithUpdateBehaviors();
        update_record.setId("id123");
        update_record.setVersion(1L);
        update_record.setNestedRecord(updatedNestedRecord1);

        mappedTable.updateItem(r -> r.item(update_record).ignoreNulls(true));

        RecordWithUpdateBehaviors persistedRecord = mappedTable.getItem(r -> r.key(k -> k.partitionValue("id123")));

        verifyMultipleLevelNestingTargetedUpdateBehavior(persistedRecord, outerNestedCounter, innerNestedCounter);
    }

    @Test
    public void when_updatingNestedNonScalarObject_DynamoDBExceptionIsThrown() {

        NestedRecordWithUpdateBehavior nestedRecord = createNestedWithDefaults("id456", 5L);
        nestedRecord.setAttribute(TEST_ATTRIBUTE);

        RecordWithUpdateBehaviors record = new RecordWithUpdateBehaviors();
        record.setId("id123");

        mappedTable.putItem(record);

        RecordWithUpdateBehaviors update_record = new RecordWithUpdateBehaviors();
        update_record.setId("id123");
        update_record.setVersion(1L);
        update_record.setKey("abc");
        update_record.setNestedRecord(nestedRecord);

        assertThatThrownBy(() -> mappedTable.updateItem(r -> r.item(update_record).ignoreNulls(true)))
            .isInstanceOf(DynamoDbException.class);
    }

    @Test
    public void when_emptyNestedRecordIsSet_emotyMapIsStoredInTable() {
        String key = "id123";

        RecordWithUpdateBehaviors record = new RecordWithUpdateBehaviors();
        record.setId(key);
        record.setNestedRecord(new NestedRecordWithUpdateBehavior());

        mappedTable.updateItem(r -> r.item(record).ignoreNulls(true));

        GetItemResponse getItemResponse = getDynamoDbClient().getItem(GetItemRequest.builder()
                                                                                    .key(Collections.singletonMap("id",
                                                                                                                  AttributeValue.fromS(key)))
                                                                                    .tableName(getConcreteTableName("table-name"))
                                                                                    .build());

        assertThat(getItemResponse.item().get("nestedRecord")).isNotNull();
        assertThat(getItemResponse.item().get("nestedRecord").toString()).isEqualTo("AttributeValue(M={nestedTimeAttribute"
                                                                                + "=AttributeValue(NUL=true), "
                                                                                + "nestedRecord=AttributeValue(NUL=true), "
                                                                                + "attribute=AttributeValue(NUL=true), "
                                                                                + "id=AttributeValue(NUL=true), "
                                                                                + "nestedUpdateBehaviorAttribute=AttributeValue"
                                                                                + "(NUL=true), nestedCounter=AttributeValue"
                                                                                + "(NUL=true), nestedVersionedAttribute"
                                                                                + "=AttributeValue(NUL=true)})");
    }


    @Test
    public void when_updatingNestedObjectWithSingleLevelFlattened_existingInformationIsPreserved() {

        NestedRecordWithUpdateBehavior nestedRecord = createNestedWithDefaults("id123", 10L);

        CompositeRecord compositeRecord = new CompositeRecord();
        compositeRecord.setNestedRecord(nestedRecord);

        FlattenRecord flattenRecord = new FlattenRecord();
        flattenRecord.setCompositeRecord(compositeRecord);
        flattenRecord.setId("id456");
        
        flattenedMappedTable.putItem(r -> r.item(flattenRecord));
        
        NestedRecordWithUpdateBehavior updateNestedRecord = new NestedRecordWithUpdateBehavior();
        updateNestedRecord.setNestedCounter(100L);
        
        CompositeRecord updateCompositeRecord = new CompositeRecord();
        updateCompositeRecord.setNestedRecord(updateNestedRecord);
        
        FlattenRecord updatedFlattenRecord = new FlattenRecord();
        updatedFlattenRecord.setId("id456");
        updatedFlattenRecord.setCompositeRecord(updateCompositeRecord);
        
        FlattenRecord persistedFlattenedRecord = flattenedMappedTable.updateItem(r -> r.item(updatedFlattenRecord).ignoreNulls(true));
        
        assertThat(persistedFlattenedRecord.getCompositeRecord()).isNotNull();
        assertThat(persistedFlattenedRecord.getCompositeRecord().getNestedRecord().getNestedCounter()).isEqualTo(100L);
    }
    
    @Test
    public void when_updatingNestedObjectWithMultipleLevelFlattened_existingInformationIsPreserved() {

        NestedRecordWithUpdateBehavior outerNestedRecord = createNestedWithDefaults("id123", 10L);
        NestedRecordWithUpdateBehavior innerNestedRecord = createNestedWithDefaults("id456", 5L);
        outerNestedRecord.setNestedRecord(innerNestedRecord);

        CompositeRecord compositeRecord = new CompositeRecord();
        compositeRecord.setNestedRecord(outerNestedRecord);

        FlattenRecord flattenRecord = new FlattenRecord();
        flattenRecord.setCompositeRecord(compositeRecord);
        flattenRecord.setId("id789");
        
        flattenedMappedTable.putItem(r -> r.item(flattenRecord));
        
        NestedRecordWithUpdateBehavior updateOuterNestedRecord = new NestedRecordWithUpdateBehavior();
        updateOuterNestedRecord.setNestedCounter(100L);
        
        NestedRecordWithUpdateBehavior updateInnerNestedRecord = new NestedRecordWithUpdateBehavior();
        updateInnerNestedRecord.setNestedCounter(50L);
        
        updateOuterNestedRecord.setNestedRecord(updateInnerNestedRecord);
        
        CompositeRecord updateCompositeRecord = new CompositeRecord();
        updateCompositeRecord.setNestedRecord(updateOuterNestedRecord);
        
        FlattenRecord updateFlattenRecord = new FlattenRecord();
        updateFlattenRecord.setCompositeRecord(updateCompositeRecord);
        updateFlattenRecord.setId("id789");
        
        FlattenRecord persistedFlattenedRecord = flattenedMappedTable.updateItem(r -> r.item(updateFlattenRecord).ignoreNulls(true));
        
        assertThat(persistedFlattenedRecord.getCompositeRecord()).isNotNull();
        assertThat(persistedFlattenedRecord.getCompositeRecord().getNestedRecord().getNestedCounter()).isEqualTo(100L);
        assertThat(persistedFlattenedRecord.getCompositeRecord().getNestedRecord().getNestedRecord().getNestedCounter()).isEqualTo(50L);
    }


    /**
     * Currently, nested records are not updated through extensions.
     */
    @Test
    public void updateBehaviors_nested() {
        NestedRecordWithUpdateBehavior nestedRecord = new NestedRecordWithUpdateBehavior();
        nestedRecord.setId("id456");

        RecordWithUpdateBehaviors record = new RecordWithUpdateBehaviors();
        record.setId("id123");
        record.setCreatedOn(INSTANT_1);
        record.setLastUpdatedOn(INSTANT_2);
        record.setNestedRecord(nestedRecord);
        mappedTable.updateItem(record);

        RecordWithUpdateBehaviors persistedRecord = mappedTable.getItem(record);

        assertThat(persistedRecord.getVersion()).isEqualTo(1L);
        assertThat(persistedRecord.getNestedRecord()).isNotNull();
        assertThat(persistedRecord.getNestedRecord().getNestedVersionedAttribute()).isNull();
        assertThat(persistedRecord.getNestedRecord().getNestedCounter()).isNull();
        assertThat(persistedRecord.getNestedRecord().getNestedUpdateBehaviorAttribute()).isNull();
        assertThat(persistedRecord.getNestedRecord().getNestedTimeAttribute()).isNull();
    }
}
