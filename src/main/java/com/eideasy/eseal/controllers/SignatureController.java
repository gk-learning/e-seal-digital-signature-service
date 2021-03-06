package com.eideasy.eseal.controllers;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.tomcat.util.buf.HexUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import com.eideasy.eseal.SignatureCreateException;
import com.eideasy.eseal.hsm.HsmSigner;
import com.eideasy.eseal.hsm.HsmSignerFactory;
import com.eideasy.eseal.models.CertificateRequest;
import com.eideasy.eseal.models.CertificateResponse;
import com.eideasy.eseal.models.PinResponse;
import com.eideasy.eseal.models.SealRequest;
import com.eideasy.eseal.models.SealResponse;
import com.eideasy.eseal.models.TimestampedRequest;

@RestController
public class SignatureController {

    private static final Logger logger = LoggerFactory.getLogger(com.eideasy.eseal.controllers.SignatureController.class);
    private static Map<String, byte[]> keyPasswordMap = new HashMap<>();

    @Autowired
   	private RestTemplate restTemplate;
    
    @Autowired
    private Environment env;

    @Autowired
    HsmSignerFactory factory;

    @PostMapping("/api/get-certificate")
    public ResponseEntity<?> getCertificate(@RequestBody CertificateRequest request) {
        CertificateResponse response = new CertificateResponse();
        String certificate;
        try {
            verifyTimestamp(request);
            verifyCertificateMac(request);
            HsmSigner hmsSigner = factory.getSigner(request.getKeyId());
            certificate = hmsSigner.getCertificate(request.getKeyId());
            response.setCertificate(certificate);
        } catch (SignatureCreateException | Exception e) {
            logger.error("Getting certificate failed", e);
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String sStackTrace = sw.toString();
            String errorMessage = e.getClass() + " " + e.getMessage() + " \n" + sStackTrace;
            response.setMessage(errorMessage);
            return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PostMapping("/api/create-seal")
    public ResponseEntity<?> createSignature(@RequestBody SealRequest request) throws SignatureCreateException {
        logger.info("Signing digest " + request.getDigest());
        String uri = env.getProperty("key_id." + request.getKeyId() + ".password_url");

        SealResponse response = new SealResponse();
        final String signAlgorithm = request.getAlgorithm(); // "SHA256withRSA" or SHA256withECDSA;
        String keyPass = env.getProperty("key_id." + request.getKeyId() + ".password");
        if (keyPass == null) {
            logger.error("Private key PIN/password not configured");
            if(keyPasswordMap.containsKey(request.getKeyId())) {
            	keyPass = new String(keyPasswordMap.get(request.getKeyId()));
            } else {
            	PinResponse pinResponse = restTemplate.getForObject(uri, PinResponse.class);
            	keyPasswordMap.put(request.getKeyId(), pinResponse.getPassword().getBytes());
            }
            response.setStatus("error");
            response.setMessage("Private key PIN/password not configured");
            return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        String base64Signature;
        try {
            verifySealMac(request);
            HsmSigner hmsSigner = factory.getSigner(request.getKeyId());
            long start = System.currentTimeMillis();
            byte[] signature = hmsSigner.signDigest(signAlgorithm, HexUtils.fromHexString(request.getDigest()), request.getKeyId(),keyPass);
            base64Signature = Base64.getEncoder().encodeToString(signature);
            long end = System.currentTimeMillis();
            logger.info("Signature done " + (end - start) + "ms. Value=" + base64Signature);
        } catch (SignatureCreateException | Exception e) {
            logger.error("E-seal creation failed", e);
            response.setStatus("error");
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String sStackTrace = sw.toString();
            String errorMessage = e.getClass() + " " + e.getMessage() + " \n" + sStackTrace;
            response.setMessage(errorMessage);
            return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
        }

        response.setSignature(base64Signature);
        response.setAlgorithm(signAlgorithm);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    protected boolean verifyCertificateMac(CertificateRequest request) throws NoSuchAlgorithmException, InvalidKeyException, SignatureCreateException {
        String message = "" + request.getKeyId() + request.getTimestamp() + "/api/get-certificate";
        return verifyMac(message, request.getHmac(), request.getKeyId());
    }

    protected boolean verifySealMac(SealRequest request) throws NoSuchAlgorithmException, InvalidKeyException, SignatureCreateException {
        String message = "" + request.getDigest() + request.getAlgorithm() + request.getKeyId() + request.getTimestamp() + "/api/create-seal";
        return verifyMac(message, request.getHmac(), request.getKeyId());
    }

    protected boolean verifyTimestamp(TimestampedRequest request) throws SignatureException {
        long currentTime = System.currentTimeMillis() / 1000;

        if (request.getTimestamp() > (currentTime + 60) || request.getTimestamp() < (currentTime - 60)) {
            String message = "Timestamp out of sync. request=" + request.getTimestamp() + " system=" + currentTime;
            logger.error(message);
            throw new SignatureException(message);
        }
        return true;
    }

    protected boolean verifyMac(String message, String hmac, String keyId) throws NoSuchAlgorithmException, InvalidKeyException, SignatureCreateException {
        // Properties must have this value configured.
        String hmacKey = env.getProperty("key_id." + keyId + ".hmac_key");
        if (hmacKey == null) {
            logger.error("Hmac key not configured");
            throw new SignatureCreateException("HMAC key not configured");
        }

        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secret_key = new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        sha256_HMAC.init(secret_key);
        String calcHmac = HexUtils.toHexString(sha256_HMAC.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        if (!calcHmac.equals(hmac)) {
            logger.error("Mac does not match");
            logger.info("Calculated mac: " + calcHmac + ", original is: " + hmac);
            logger.info("Message: " + message + " key=" + hmacKey.substring(0, 5));
            throw new SignatureCreateException("HMAC not matching");
        }

        return true;
    }
    
}
