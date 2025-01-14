package com.jrasp.core.manager.impl;

import com.alibaba.fastjson.JSONObject;
import com.jrasp.api.authentication.JwtTokenService;
import com.jrasp.api.authentication.PayloadDto;
import com.jrasp.api.log.Log;
import com.jrasp.core.log.LogFactory;
import org.jose4j.jwe.ContentEncryptionAlgorithmIdentifiers;
import org.jose4j.jwe.JsonWebEncryption;
import org.jose4j.jwe.KeyManagementAlgorithmIdentifiers;
import org.jose4j.keys.PbkdfKey;
import org.jose4j.lang.JoseException;

import java.sql.Driver;

import static com.jrasp.core.log.AgentLogIdConstant.AGENT_COMMON_LOG_ID;

public class JwtTokenServiceImpl implements JwtTokenService {

    private final Log logger = LogFactory.getLog(getClass());

    public static JwtTokenServiceImpl instance = new JwtTokenServiceImpl();

    private final static String password = "don't-tell-p@ul|pam!";

    @Override
    public PayloadDto getDefaultPayloadDto(String username) {
        long start = System.currentTimeMillis();
        long end = start + 5 * 60 * 1000;
        return new PayloadDto(username, start, end);
    }

    @Override
    public String generateToken(String payloadStr) {
        JsonWebEncryption jwe = new JsonWebEncryption();
        jwe.setPayload(payloadStr);
        jwe.setKey(new PbkdfKey(password));
        jwe.setAlgorithmHeaderValue(KeyManagementAlgorithmIdentifiers.PBES2_HS256_A128KW);
        jwe.setEncryptionMethodHeaderParameter(ContentEncryptionAlgorithmIdentifiers.AES_128_CBC_HMAC_SHA_256);
        String compactSerialization = "";
        try {
            compactSerialization = jwe.getCompactSerialization();
        } catch (JoseException e) {
            logger.error(AGENT_COMMON_LOG_ID,"generate token error", e);
        }
        return compactSerialization;
    }

    @Override
    public boolean verifyToken(String token) {
        JsonWebEncryption jwe = new JsonWebEncryption();
        String payload = "";
        try {
            jwe.setCompactSerialization(token);
            jwe.setKey(new PbkdfKey(password));
            payload = jwe.getPayload();
        } catch (Exception e) {
            logger.error(AGENT_COMMON_LOG_ID,"verify token error", e);
        }
        PayloadDto payloadDto = JSONObject.parseObject(payload, PayloadDto.class);
        return System.currentTimeMillis() <= payloadDto.getEnd();
    }
}
