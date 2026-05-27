package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QslAiPromptDefaultsTest {

    @Test
    void callbookAddressPromptShouldDefineReturnFieldsAndQrzComMapping() {
        var prompt = QslAiPromptDefaults.CALLBOOK_ADDRESS_PROMPT;

        assertTrue(prompt.contains("callSign、recipientName、telephone、postalCode、address、email、confidence、message"));
        assertTrue(prompt.contains("address 必须优先包含最详细地址字段 addr1"));
        assertTrue(prompt.contains("若 addr1 已经是完整中文地址，不要退化为只返回 country、state 或 addr2"));
        assertTrue(prompt.contains("{provider}"));
        assertTrue(prompt.contains("{callSign}"));
        assertTrue(prompt.contains("{features}"));
    }
}
