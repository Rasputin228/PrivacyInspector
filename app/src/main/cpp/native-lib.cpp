#include <jni.h>
#include <string>
#include <netinet/in.h>
#include <netinet/ip.h>
#include <netinet/udp.h>

// Простой парсер имени домена
std::string parse_dns_qname(const unsigned char* buffer, int len, int offset) {
    std::string name = "";
    int pos = offset;
    int jumps = 0; // Защита от бесконечных циклов

    while (pos < len && buffer[pos] != 0) {
        if (jumps++ > 128) return ""; // Слишком длинное имя

        int labelLen = buffer[pos];

        // Обработка сжатия DNS (если встретили указатель)
        if ((labelLen & 0xC0) == 0xC0) {
            // Для простоты курсовой: если встретили сжатие, прекращаем парсинг,
            // чтобы не усложнять код. Основное имя обычно идет в начале.
            return name;
        }

        if (!name.empty()) name += ".";
        pos++;

        for (int i = 0; i < labelLen; i++) {
            if (pos >= len) return "";
            name += (char)buffer[pos];
            pos++;
        }
    }
    return name;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_privacyinspector_PrivacyVpnService_nativeGetDomain(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray packet) {

    jbyte* bufferPtr = env->GetByteArrayElements(packet, NULL);
    jsize length = env->GetArrayLength(packet);
    unsigned char* buffer = (unsigned char*)bufferPtr;

    std::string domain = "";

    // Минимальный размер: IP(20) + UDP(8) + DNS Header(12) = 40 байт
    if (length > 40) {
        struct iphdr *ip = (struct iphdr *)buffer;

        // Проверяем версию IP (4) и протокол UDP (17)
        if (ip->version == 4 && ip->protocol == 17) {
            int ipHeadLen = ip->ihl * 4;
            int udpHeadLen = 8;
            int dnsHeadLen = 12;

            // Имя (Question) начинается сразу после заголовка DNS
            int questionStart = ipHeadLen + udpHeadLen + dnsHeadLen;

            if (questionStart < length) {
                domain = parse_dns_qname(buffer, length, questionStart);
            }
        }
    }

    env->ReleaseByteArrayElements(packet, bufferPtr, 0);

    if (domain.empty()) return NULL;
    return env->NewStringUTF(domain.c_str());
}