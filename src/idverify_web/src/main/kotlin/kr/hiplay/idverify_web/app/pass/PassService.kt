package kr.hiplay.idverify_web.app.pass

import kr.hiplay.idverify_web.utils.CryptoUtil

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

import io.viascom.nanoid.NanoId
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

import java.io.File
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.PrivateKey
import java.security.Signature
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.stream.Collectors


interface IInitData {
    val siteCd: String
    val webSiteId: String
    val webSiteIdHashing: String
    val callbackUrl: String
    val kcpBaseUrl: String
}

interface IResponseBase {
    val resCd: String
    val resMsg: String
}

interface IHashData : IResponseBase {
    val upHash: String
    val orderId: String
    val kcpCertLibVer: String
    val kcpMerchantTime: String
}

interface IDecryptData : IResponseBase {
    val certKey: String
    val certIv: String
    val authData: String
    val specName: String
    val redirectUrl: String
}

@Service
class PassService {
    private val _siteCd = "AO0QE"
    private val _webSiteId = ""
    private val _webSiteIdHashing = "Y"

    private val _kcpApiBaseUrl = "https://stg-spl.kcp.co.kr" // KCP 테스트 서버

    //    private val _kcpApiBaseUrl = "https://spl.kcp.co.kr"; // KCP 운영 서버
    private val _kcpApiReqUrl = URL("$_kcpApiBaseUrl/std/certpass") // KCP API 요청 URL

    private val httpClient = HttpClient.newBuilder().build()

    private fun getSerializedCert(certType: String): String {
        val certFile = when {
            (certType === "private") -> File(
                this::class.java.classLoader.getResource("kcp/certificate/splPrikeyPKCS8.pem")?.toURI()!!
            )

            else -> File(this::class.java.classLoader.getResource("kcp/certificate/splCert.pem")?.toURI()!!)
        }

        return certFile.readText(Charsets.UTF_8).replace("\r", "").replace("\n", "")
    }

    private fun getCurrentTime(): String {
        val currentTime = LocalDateTime.now(ZoneId.of("Asia/Seoul"))
        val timeFormatter = DateTimeFormatter.ofPattern("YYMMddHHmmss")

        return currentTime.format(timeFormatter)
    }

    private fun loadSplMctPrivateKeyPKCS8(): PrivateKey {
        val file = File(this::class.java.classLoader.getResource("kcp/certificate/splPrikeyPKCS8.pem")?.toURI()!!)
        val privateKeyPassword = "changeit"

        val strPriKeyData = file.readLines()
            .stream()
            .filter { line: String ->
                !line.startsWith("-----BEGIN") && !line.startsWith(
                    "-----END"
                )
            }
            .collect(Collectors.joining()).replace("\r", "").replace("\n", "")

        val btArrPriKey = Base64.getDecoder().decode(strPriKeyData)

        val derSeq = ASN1Sequence.getInstance(btArrPriKey)
        val encPkcs8PriKeyInfo =
            PKCS8EncryptedPrivateKeyInfo(org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo.getInstance(derSeq))

        val pemKeyConverter = JcaPEMKeyConverter()
        val decProvider =
            JceOpenSSLPKCS8DecryptorProviderBuilder().build(privateKeyPassword.toCharArray())
        val priKeyInfo = encPkcs8PriKeyInfo.decryptPrivateKeyInfo(decProvider)

        return pemKeyConverter.getPrivateKey(priKeyInfo)
    }

    private fun makeSignatureData(targetData: String): String {
        val priKey: PrivateKey = loadSplMctPrivateKeyPKCS8()
        val btArrTargetData = targetData.toByteArray(Charsets.UTF_8)

        val sign = Signature.getInstance("SHA256WithRSA")
        sign.initSign(priKey)
        sign.update(btArrTargetData)

        val btArrSignData: ByteArray = sign.sign()

        return Base64.getEncoder().encodeToString(btArrSignData)
    }

    @Transactional
    fun createOrder(): String {
        val orderId = NanoId.generate()

        // TODO: 요청 정보 DB처리

        return orderId
    }

    @Transactional
    fun getInitialData(): IInitData {
        return object : IInitData {
            override val siteCd = _siteCd
            override val webSiteId = _webSiteId
            override val webSiteIdHashing = _webSiteIdHashing
            override val callbackUrl = "http://localhost:8080/pass/callback.html"
            override val kcpBaseUrl = "https://testcert.kcp.co.kr" // KCP 테스트 서버
            // override val kcpBaseUrl = "https://cert.kcp.co.kr" // KCP 운영 서버
        }
    }

    @Transactional
    fun getHash(orderId: String): IHashData {
        val ctType = "HAS"
        val taxNo = "000000"

        val formattedTime = getCurrentTime()
        val hashInfo = "${_siteCd}^${ctType}^${taxNo}^${formattedTime}"
        val hashData = makeSignatureData(hashInfo)

        val requestBody = buildJsonObject {
            put("kcp_cert_info", getSerializedCert("public"))
            put("site_cd", _siteCd)
            put("ordr_idxx", orderId)
            put("ct_type", ctType)
            put("web_siteid", _webSiteId)
            put("tax_no", taxNo)
            put("make_req_dt", formattedTime)
            put("kcp_sign_data", hashData)
        }


        val request = HttpRequest.newBuilder()
            .uri(_kcpApiReqUrl.toURI())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val responseBody = jacksonObjectMapper().readTree(response.body())

        return object : IHashData {
            override val orderId = orderId
            override val resCd = responseBody.get("res_cd").asText()
            override val resMsg = responseBody.get("res_msg").asText()
            override val upHash = responseBody.get("up_hash").asText()
            override val kcpCertLibVer = responseBody.get("kcp_cert_lib_ver").asText()
            override val kcpMerchantTime = responseBody.get("kcp_merchant_time").asText()
        }
    }

    @Transactional
    fun validateHash(orderId: String, certNo: String, dnHash: String): IResponseBase {
        val ctType = "CHK"

        val hashInfo = "${_siteCd}^${ctType}^${certNo}^${dnHash}"
        val hashData = makeSignatureData(hashInfo)

        val requestBody = buildJsonObject {
            put("kcp_cert_info", getSerializedCert("public"))
            put("site_cd", _siteCd)
            put("ordr_idxx", orderId)
            put("ct_type", ctType)
            put("dn_hash", dnHash)
            put("cert_no", certNo)
            put("kcp_sign_data", hashData)
        }

        val request = HttpRequest.newBuilder()
            .uri(_kcpApiReqUrl.toURI())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val responseBody = jacksonObjectMapper().readTree(response.body())

        // TODO: 요청 정보 DB처리

        return object : IResponseBase {
            override val resCd = responseBody.get("res_cd").asText()
            override val resMsg = responseBody.get("res_msg").asText()
        }
    }

    @Transactional
    fun decryptUserData(orderId: String, certNo: String, encCertData: String, clientService: String): IResponseBase {
        val ctType = "DEC"

        val hashInfo = "${_siteCd}^${ctType}^${certNo}"
        val hashData = makeSignatureData(hashInfo)

        val requestBody = buildJsonObject {
            put("kcp_cert_info", getSerializedCert("public"))
            put("site_cd", _siteCd)
            put("ordr_idxx", orderId)
            put("ct_type", ctType)
            put("cert_no", certNo)
            put("enc_cert_Data", encCertData)
            put("kcp_sign_data", hashData)
        }

        val request = HttpRequest.newBuilder()
            .uri(_kcpApiReqUrl.toURI())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val responseBody = jacksonObjectMapper().readTree(response.body())

        val specName = "AES/CBC/PKCS5Padding"
        val certKey = ""
        val certIv = ""

        return object : IDecryptData {
            override val resCd = responseBody.get("res_cd").asText()
            override val resMsg = responseBody.get("res_msg").asText()

            override val authData: String
                get() {
                    val result = buildJsonObject {
                        put("telecomId", responseBody.get("comm_id").asText())

                        put("userName", responseBody.get("user_name").asText())
                        put("userPhone", responseBody.get("phone_no").asText())
                        put("userBirth", responseBody.get("birth_day").asText())
                        put(
                            "userGender", when {
                                responseBody.get("sex_code").asText() === "01" -> "M"
                                responseBody.get("sex_code").asText() === "02" -> "F"
                                else -> "E"
                            }
                        )
                        put(
                            "userNationality", when {
                                responseBody.get("local_code").asText() === "01" -> "KR"
                                else -> "ETC"
                            }
                        )

                        put("ci", responseBody.get("ci_url").asText())
                        put("di", responseBody.get("di_url").asText())
                    }

                    return CryptoUtil().AESEncrypt(
                        specName,
                        certKey,
                        certIv,
                        buildJsonObject {
                            put("pass", result)
                        }.toString()
                    )
                }

            override val specName = specName
            override val certKey = certKey
            override val certIv = certIv
            override val redirectUrl: String
                get() = TODO("Not yet implemented")
        }
    }
}
