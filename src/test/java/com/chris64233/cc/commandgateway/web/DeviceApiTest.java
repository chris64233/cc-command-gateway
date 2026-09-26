package com.chris64233.cc.commandgateway.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DeviceApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fullLeaseCommandReceiptFlowOverHttp() throws Exception {
        String deviceNumber = "dev-http-flow";

        mockMvc.perform(post("/api/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceNumber\":\"" + deviceNumber + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceNumber").value(deviceNumber))
                .andExpect(jsonPath("$.fencingToken").value(0))
                .andExpect(jsonPath("$.currentLease").doesNotExist());

        MvcResult leaseResult = mockMvc.perform(post("/api/devices/{device}/leases", deviceNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"client-a\",\"ttlMillis\":60000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fencingToken").value(1))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        JsonNode lease = objectMapper.readTree(leaseResult.getResponse().getContentAsString());
        long leaseId = lease.get("id").asLong();
        long token = lease.get("fencingToken").asLong();

        String commandBody = "{\"leaseId\":" + leaseId + ",\"fencingToken\":" + token
                + ",\"sequence\":1,\"idempotencyKey\":\"k1\",\"payload\":\"reboot\"}";
        MvcResult commandResult = mockMvc.perform(post("/api/devices/{device}/commands", deviceNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn();
        long commandId = objectMapper.readTree(commandResult.getResponse().getContentAsString())
                .get("id").asLong();

        mockMvc.perform(post("/api/devices/{device}/commands", deviceNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commandBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(commandId));

        mockMvc.perform(post("/api/devices/{device}/commands/{id}/receipts", deviceNumber, commandId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"evt-1\",\"fencingToken\":" + token
                                + ",\"status\":\"SUCCEEDED\",\"detail\":\"done\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        mockMvc.perform(get("/api/devices/{device}", deviceNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fencingToken").value(1))
                .andExpect(jsonPath("$.currentLease.id").value(leaseId))
                .andExpect(jsonPath("$.currentLease.lastAcceptedSequence").value(1));

        mockMvc.perform(get("/api/devices/{device}/commands", deviceNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(commandId))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].receipts.length()").value(1))
                .andExpect(jsonPath("$[0].receipts[0].eventId").value("evt-1"));
    }

    @Test
    void rejectsStaleTokenAndUnknownDeviceWithErrorBody() throws Exception {
        String deviceNumber = "dev-http-errors";

        mockMvc.perform(get("/api/devices/{device}", deviceNumber))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));

        mockMvc.perform(post("/api/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceNumber\":\"" + deviceNumber + "\"}"))
                .andExpect(status().isCreated());

        MvcResult leaseResult = mockMvc.perform(post("/api/devices/{device}/leases", deviceNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"client-a\",\"ttlMillis\":60000}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode lease = objectMapper.readTree(leaseResult.getResponse().getContentAsString());

        mockMvc.perform(post("/api/devices/{device}/commands", deviceNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseId\":" + lease.get("id").asLong()
                                + ",\"fencingToken\":99,\"sequence\":1"
                                + ",\"idempotencyKey\":\"k1\",\"payload\":\"p\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FENCING_TOKEN_MISMATCH"));

        MvcResult conflict = mockMvc.perform(post("/api/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceNumber\":\"" + deviceNumber + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_EXISTS"))
                .andReturn();
        assertThat(conflict.getResponse().getContentAsString()).contains("timestamp");
    }

    @Test
    void rejectsInvalidRequestPayload() throws Exception {
        mockMvc.perform(post("/api/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceNumber\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
