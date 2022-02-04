package org.bf2.admin.kafka.systemtest.oauth;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.bf2.admin.kafka.admin.KafkaAdminConfigRetriever;
import org.bf2.admin.kafka.systemtest.TestOAuthProfile;
import org.bf2.admin.kafka.systemtest.deployment.DeploymentManager.UserType;
import org.bf2.admin.kafka.systemtest.utils.ConsumerUtils;
import org.bf2.admin.kafka.systemtest.utils.TokenUtils;
import org.eclipse.microprofile.config.Config;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

import javax.inject.Inject;
import javax.json.Json;
import javax.ws.rs.core.Response.Status;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(TestOAuthProfile.class)
class PartitionsOffsetOauthIT {

    static final String CONSUMER_GROUP_RESET_PATH = "/rest/consumer-groups/{groupId}/reset-offset";

    @Inject
    Config config;

    String bootstrapServers;
    TokenUtils tokenUtils;
    ConsumerUtils consumerUtils;

    @BeforeEach
    void setup() {
        bootstrapServers = config.getValue(KafkaAdminConfigRetriever.BOOTSTRAP_SERVERS, String.class);
        tokenUtils = new TokenUtils(config.getValue(KafkaAdminConfigRetriever.OAUTH_TOKEN_ENDPOINT_URI, String.class));
        consumerUtils = new ConsumerUtils(bootstrapServers, tokenUtils.getToken(UserType.OWNER.getUsername()));
    }

    @Test
    void testResetOffsetToStartWithOpenClient() {
        final String batchId = UUID.randomUUID().toString();
        final String topicName = "t-" + batchId;
        final String groupId = "g-" + batchId;
        final String clientId = "c-" + batchId;

        try (var consumer = consumerUtils.request().groupId(groupId).topic(topicName).clientId(clientId).produceMessages(5).consume()) {
            given()
                .log().ifValidationFails()
                .contentType(ContentType.JSON)
                .header(tokenUtils.authorizationHeader(UserType.OWNER.getUsername()))
                .body(Json.createObjectBuilder()
                      .add("offset", "earliest")
                      .add("value", "")
                      .add("topics", Json.createArrayBuilder()
                           .add(Json.createObjectBuilder()
                                .add("topic", topicName)))
                      .build()
                      .toString())
            .when()
                .post(CONSUMER_GROUP_RESET_PATH, groupId)
            .then()
                .log().ifValidationFails()
                .statusCode(Status.BAD_REQUEST.getStatusCode())
            .assertThat()
                .body("code", equalTo(Status.BAD_REQUEST.getStatusCode()))
                .body("error_message", Matchers.matchesPattern(".*connected clients.*"));
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "topic1 | topicBad | 1 | .*Request contained an unknown topic.* | false",
        "topic1 | topic1   | 5 | .*Topic topic1%s, partition 5 is not valid.* | true",
    })
    void testResetOffsetToStartWithInvalidTopicPartition(String topicPrefix, String topicResetPrefix, int resetPartition, String messagePattern, boolean format) {
        final String batchId = UUID.randomUUID().toString();
        final String topicName = topicPrefix + batchId;
        final String topicResetName = topicResetPrefix + batchId;
        final String groupId = "g-" + batchId;
        final String clientId = "c-" + batchId;
        String expectedMessage = format ? String.format(messagePattern, batchId) : messagePattern;

        consumerUtils.request()
            .groupId(groupId)
            .topic(topicName)
            .clientId(clientId)
            .produceMessages(1)
            .autoClose(true)
            .consume();

        given()
            .log().ifValidationFails()
            .contentType(ContentType.JSON)
            .header(tokenUtils.authorizationHeader(UserType.OWNER.getUsername()))
            .body(Json.createObjectBuilder()
                  .add("offset", "earliest")
                  .add("value", "")
                  .add("topics", Json.createArrayBuilder()
                       .add(Json.createObjectBuilder()
                            .add("topic", topicResetName)
                            .add("partitions", Json.createArrayBuilder().add(resetPartition))))
                  .build()
                  .toString())
        .when()
            .post(CONSUMER_GROUP_RESET_PATH, groupId)
        .then()
            .log().ifValidationFails()
            .statusCode(Status.BAD_REQUEST.getStatusCode())
        .assertThat()
            .body("code", equalTo(Status.BAD_REQUEST.getStatusCode()))
            .body("error_message", Matchers.matchesPattern(expectedMessage));
    }

    @Test
    void testResetOffsetUnauthorized() throws InterruptedException {
        final String batchId = UUID.randomUUID().toString();
        final String topicName = "t-" + batchId;
        final String groupId = "g-" + batchId;
        final String clientId = "c-" + batchId;

        consumerUtils.request()
            .groupId(groupId)
            .topic(topicName)
            .clientId(clientId)
            .produceMessages(10)
            .autoClose(true)
            .consume();

        given()
            .log().ifValidationFails()
            .contentType(ContentType.JSON)
            .header(tokenUtils.authorizationHeader(UserType.OTHER.getUsername())) // Other user without access to reset offsets
            .body(Json.createObjectBuilder()
                  .add("offset", "latest")
                  .add("value", "")
                  .add("topics", Json.createArrayBuilder()
                       .add(Json.createObjectBuilder()
                            .add("topic", topicName)))
                  .build()
                  .toString())
        .when()
            .post(CONSUMER_GROUP_RESET_PATH, groupId)
        .then()
            .log().ifValidationFails()
            .statusCode(Status.FORBIDDEN.getStatusCode())
        .assertThat()
            .body("code", equalTo(Status.FORBIDDEN.getStatusCode()))
            .body("error_message", Matchers.notNullValue());
    }

    @ParameterizedTest
    @CsvSource({
        "earliest, 10, 10, '',  0,  10", // Reset to earliest offset
        "latest,   10,  5, '', 10,   0", // Reset to latest offset
        "absolute, 10, 10,  5,  5,   5"  // Reset to absolute offset
    })
    void testResetOffsetAuthorized(String offset, int produceCount, int consumeCount, String value, int expectedOffset, int expectedMessageConsumeCount) throws InterruptedException {
        final String batchId = UUID.randomUUID().toString();
        final String topicName = "t-" + batchId;
        final String groupId = "g-" + batchId;
        final String clientId = "c-" + batchId;

        consumerUtils.request()
            .groupId(groupId)
            .topic(topicName)
            .clientId(clientId)
            .produceMessages(produceCount)
            .consumeMessages(consumeCount)
            .autoClose(true)
            .consume();

        given()
            .log().ifValidationFails()
            .contentType(ContentType.JSON)
            .header(tokenUtils.authorizationHeader(UserType.OWNER.getUsername()))
            .body(Json.createObjectBuilder()
                  .add("offset", offset)
                  .add("value", value)
                  .add("topics", Json.createArrayBuilder()
                       .add(Json.createObjectBuilder()
                            .add("topic", topicName)))
                  .build()
                  .toString())
        .when()
            .post(CONSUMER_GROUP_RESET_PATH, groupId)
        .then()
            .log().ifValidationFails()
            .statusCode(Status.OK.getStatusCode())
        .assertThat()
            .body("items", hasSize(1))
            .body("items.find { it }.topic", equalTo(topicName))
            .body("items.find { it }.partition", equalTo(0))
            .body("items.find { it }.offset", equalTo(expectedOffset));

        var consumer = consumerUtils.request()
            .groupId(groupId)
            .topic(topicName)
            .createTopic(false)
            .clientId(clientId)
            .autoClose(true)
            .consume();

        assertEquals(expectedMessageConsumeCount, consumer.records().size());
    }

    @Test
    void testResetOffsetToTimestampAuthorized() throws InterruptedException {
        int firstBatchSize = 6;
        int secondBatchSize = 7;
        final String batchId = UUID.randomUUID().toString();
        final String topicName = "t-" + batchId;
        final String groupId = "g-" + batchId;
        final String clientId = "c-" + batchId;

        consumerUtils.request()
            .groupId(groupId)
            .topic(topicName)
            .clientId(clientId)
            .produceMessages(firstBatchSize)
            .autoClose(true)
            .consume();

        Thread.sleep(3000);

        String resetTimestamp = ZonedDateTime.now(ZoneOffset.UTC).withNano(0).toString();

        consumerUtils.request()
            .groupId(groupId)
            .topic(topicName)
            .createTopic(false)
            .clientId(clientId)
            .produceMessages(secondBatchSize)
            .autoClose(true)
            .consume();

        given()
            .log().ifValidationFails()
            .contentType(ContentType.JSON)
            .header(tokenUtils.authorizationHeader(UserType.OWNER.getUsername()))
            .body(Json.createObjectBuilder()
                  .add("offset", "timestamp")
                  .add("value", resetTimestamp)
                  .add("topics", Json.createArrayBuilder()
                       .add(Json.createObjectBuilder()
                            .add("topic", topicName)))
                  .build()
                  .toString())
        .when()
            .post(CONSUMER_GROUP_RESET_PATH, groupId)
        .then()
            .log().ifValidationFails()
            .statusCode(Status.OK.getStatusCode())
        .assertThat()
            .body("items", hasSize(1))
            .body("items.find { it }.topic", equalTo(topicName))
            .body("items.find { it }.partition", equalTo(0))
            .body("items.find { it }.offset", equalTo(firstBatchSize));

        var consumer = consumerUtils.request()
            .groupId(groupId)
            .topic(topicName)
            .createTopic(false)
            .clientId(clientId)
            .autoClose(true)
            .consume();

        assertEquals(secondBatchSize, consumer.records().size());
    }

    @Test
    void testResetOffsetOnMultiplePartitionsAuthorized() throws Exception {
        final String batchId = UUID.randomUUID().toString();
        final String topicName = "t-" + batchId;
        final String groupId = "g-" + batchId;
        final String clientId = "c-" + batchId;

        var consumer1 = consumerUtils.request()
            .groupId(groupId)
            .topic(topicName, 3)
            .clientId(clientId)
            .produceMessages(20)
            .autoClose(true)
            .consume();

        assertEquals(20, consumer1.records().size());

        given()
            .log().ifValidationFails()
            .contentType(ContentType.JSON)
            .header(tokenUtils.authorizationHeader(UserType.OWNER.getUsername()))
            .body(Json.createObjectBuilder()
                  .add("offset", "absolute")
                  .add("value", "0")
                  .add("topics", Json.createArrayBuilder()
                       .add(Json.createObjectBuilder()
                            .add("topic", topicName)
                            .add("partitions", Json.createArrayBuilder()
                                 .add(0)
                                 .add(1)
                                 .add(2))))
                  .build()
                  .toString())
        .when()
            .post(CONSUMER_GROUP_RESET_PATH, groupId)
        .then()
            .log().ifValidationFails()
            .statusCode(Status.OK.getStatusCode())
        .assertThat()
            .body("items", hasSize(3))
            .body("items.findAll { it }.topic", Matchers.contains(topicName, topicName, topicName))
            .body("items.findAll { it }.partition", Matchers.containsInAnyOrder(0, 1, 2))
            .body("items.findAll { it }.offset", Matchers.contains(0, 0, 0));

        var consumer2 = consumerUtils.request()
            .groupId(groupId)
            .topic(topicName)
            .createTopic(false)
            .clientId(clientId)
            .autoClose(true)
            .consume();

        assertEquals(20, consumer2.records().size());

        // ConsumerRecord does not implement equals - string representation (brittle...)
        assertEquals(consumer1.records().toString(), consumer2.records().toString());
    }

}
