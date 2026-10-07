// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <mutex>

#include <openssl/bio.h>
#include <openssl/err.h>
#include <openssl/ssl.h>
#include <openssl/x509.h>

#include "common/fs/file.h"
#include "common/hex_util.h"
#include "common/string_util.h"

#include "core/hle/service/sockets/sfdnsres.h"
#include "core/hle/service/ssl/ssl_backend.h"
#include "core/internal_network/network.h"
#include "core/internal_network/sockets.h"

#ifdef YUZU_BUNDLED_OPENSSL
#include <openssl/cert.h>
#endif

using namespace Common::FS;

namespace Service::SSL {

// Import OpenSSL's `SSL` type into the namespace.  This is needed because the
// namespace is also named `SSL`.
using ::SSL;

namespace {

std::once_flag one_time_init_flag;
bool one_time_init_success = false;

SSL_CTX* ssl_ctx;
IOFile key_log_file; // only open if SSLKEYLOGFILE set in environment
BIO_METHOD* bio_meth;

Result CheckOpenSSLErrors();
void OneTimeInit();
void OneTimeInitLogFile();
bool OneTimeInitBIO();

#ifdef YUZU_BUNDLED_OPENSSL
// This is ported from httplib
struct scope_exit {
  explicit scope_exit(std::function<void(void)> &&f)
      : exit_function(std::move(f)), execute_on_destruction{true} {}

  scope_exit(scope_exit &&rhs) noexcept
      : exit_function(std::move(rhs.exit_function)),
        execute_on_destruction{rhs.execute_on_destruction} {
    rhs.release();
  }

  ~scope_exit() {
    if (execute_on_destruction) { this->exit_function(); }
  }

  void release() { this->execute_on_destruction = false; }

private:
  scope_exit(const scope_exit &) = delete;
  void operator=(const scope_exit &) = delete;
  scope_exit &operator=(scope_exit &&) = delete;

  std::function<void(void)> exit_function;
  bool execute_on_destruction;
};

inline X509_STORE *CreateCaCertStore(const char *ca_cert,
                                                    std::size_t size) {
    auto mem = BIO_new_mem_buf(ca_cert, static_cast<int>(size));
    auto se = scope_exit([&] { BIO_free_all(mem); });
    if (!mem) { return nullptr; }

    auto inf = PEM_X509_INFO_read_bio(mem, nullptr, nullptr, nullptr);
    if (!inf) { return nullptr; }

    auto cts = X509_STORE_new();
    if (cts) {
        for (auto i = 0; i < static_cast<int>(sk_X509_INFO_num(inf)); i++) {
            auto itmp = sk_X509_INFO_value(inf, i);
            if (!itmp) { continue; }

            if (itmp->x509) { X509_STORE_add_cert(cts, itmp->x509); }
            if (itmp->crl) { X509_STORE_add_crl(cts, itmp->crl); }
        }
    }

    sk_X509_INFO_pop_free(inf, X509_INFO_free);
    return cts;
}

inline void SetCaCertStore(SSL_CTX *ctx, X509_STORE *ca_cert_store) {
    if (ca_cert_store) {
        if (ctx) {
            if (SSL_CTX_get_cert_store(ctx) != ca_cert_store) {
                // Free memory allocated for old cert and use new store `ca_cert_store`
                SSL_CTX_set_cert_store(ctx, ca_cert_store);
            }
        } else {
            X509_STORE_free(ca_cert_store);
        }
    }
}

inline void LoadCaCertStore(SSL_CTX* ctx, const char* ca_cert, std::size_t size)
{
    SetCaCertStore(ctx, CreateCaCertStore(ca_cert, size));
}
#endif

} // namespace

class SSLConnectionBackendOpenSSL final : public SSLConnectionBackend {
public:
    Result Init() {
        // on bundled OpenSSL, load ca cert store
#ifdef YUZU_BUNDLED_OPENSSL
        LoadCaCertStore(ssl_ctx, kCert, sizeof(kCert));
#endif
        std::call_once(one_time_init_flag, OneTimeInit);

        if (!one_time_init_success) {
            LOG_ERROR(Service_SSL,
                      "Can't create SSL connection because OpenSSL one-time initialization failed");
            return ResultInternalError;
        }

        ssl = SSL_new(ssl_ctx);
        if (!ssl) {
            LOG_ERROR(Service_SSL, "SSL_new failed");
            return CheckOpenSSLErrors();
        }

        SSL_set_connect_state(ssl);

        bio = BIO_new(bio_meth);
        if (!bio) {
            LOG_ERROR(Service_SSL, "BIO_new failed");
            return CheckOpenSSLErrors();
        }

        BIO_set_data(bio, this);
        BIO_set_init(bio, 1);
        SSL_set_bio(ssl, bio, bio);

        return ResultSuccess;
    }

    void SetSocket(std::shared_ptr<Network::SocketBase> socket_in) override {
        socket = std::move(socket_in);
    }

    Result SetHostName(const std::string& hostname_in) override {
        std::string hostname = hostname_in;
        // Some titles hand over the address they resolved instead of the name. When that address
        // stands in for a Nintendo host (Nextendo Network), present the real name as the SNI.
        if (const std::string redirected = RedirectedHostOfPeer();
            !redirected.empty() && (hostname.empty() || hostname == PeerAddress())) {
            hostname = redirected;
        }
        if (hostname.empty()) {
            return ResultSuccess;
        }
        if (!skip_cert_verification) {
            if (!SSL_set1_host(ssl, hostname.c_str())) {
                LOG_ERROR(Service_SSL, "SSL_set1_host({}) failed", hostname);
                return CheckOpenSSLErrors();
            }
        }
        if (!SSL_set_tlsext_host_name(ssl, hostname.c_str())) { // hostname for SNI
            LOG_ERROR(Service_SSL, "SSL_set_tlsext_host_name({}) failed", hostname);
            return CheckOpenSSLErrors();
        }
        return ResultSuccess;
    }

    void SetAlpnProtocols(std::span<const u8> wire) override {
        requested_alpn.assign(wire.begin(), wire.end());
    }

    s32 Pending() override {
        return SSL_pending(ssl);
    }

    Result Peek(size_t* out_size, std::span<u8> data) override {
        const int ret = SSL_peek_ex(ssl, data.data(), data.size(), out_size);
        return HandleReturn("SSL_peek_ex", out_size, ret);
    }

    void SetVerifyOption(u32 option) override {
        skip_cert_verification = (option == 0);
        LOG_WARNING(Service_SSL, "option={} skip_verification={}", option,
                    skip_cert_verification);
        if (skip_cert_verification) {
            SSL_set_verify(ssl, SSL_VERIFY_NONE, nullptr);
            SSL_set1_host(ssl, nullptr);
            SSL_set_hostflags(ssl, 0);
        } else {
            SSL_set_verify(ssl, SSL_VERIFY_PEER, nullptr);
        }
    }

    Result DoHandshake() override {
        if (nextendo_host.empty()) {
            if (const std::string redirected = RedirectedHostOfPeer(); !redirected.empty()) {
                nextendo_host = redirected;
                PrepareNextendoHandshake(redirected);
            }
        }

        SSL_set_verify_result(ssl, X509_V_OK);
        const int ret = SSL_do_handshake(ssl);
        if (!nextendo_host.empty()) {
            LogNextendoHandshake(ret);
        }

        if (!skip_cert_verification) {
            const long verify_result = SSL_get_verify_result(ssl);
            if (verify_result != X509_V_OK) {
                LOG_ERROR(Service_SSL, "SSL cert verification failed because: {}",
                          X509_verify_cert_error_string(verify_result));
                return CheckOpenSSLErrors();
            }
        }

        if (ret <= 0) {
            const int ssl_err = SSL_get_error(ssl, ret);
            if (ssl_err == SSL_ERROR_ZERO_RETURN ||
                (ssl_err == SSL_ERROR_SYSCALL && got_read_eof)) {
                LOG_ERROR(Service_SSL, "SSL handshake failed because server hung up");
                return ResultInternalError;
            }
        }
        return HandleReturn("SSL_do_handshake", 0, ret);
    }

    Result Read(size_t* out_size, std::span<u8> data) override {
        const int ret = SSL_read_ex(ssl, data.data(), data.size(), out_size);
        const Result res = HandleReturn("SSL_read_ex", out_size, ret);
        if (res == ResultSuccess) {
            bytes_read += *out_size;
        }
        return res;
    }

    Result Write(size_t* out_size, std::span<const u8> data) override {
        const int ret = SSL_write_ex(ssl, data.data(), data.size(), out_size);
        const Result res = HandleReturn("SSL_write_ex", out_size, ret);
        if (res == ResultSuccess) {
            bytes_written += *out_size;
        }
        return res;
    }

    Result HandleReturn(const char* what, size_t* actual, int ret) {
        const int ssl_err = SSL_get_error(ssl, ret);
        CheckOpenSSLErrors();
        switch (ssl_err) {
        case SSL_ERROR_NONE:
            return ResultSuccess;
        case SSL_ERROR_ZERO_RETURN:
            LOG_DEBUG(Service_SSL, "{} => SSL_ERROR_ZERO_RETURN", what);
            // DoHandshake special-cases this, but for Read and Write:
            *actual = 0;
            return ResultSuccess;
        case SSL_ERROR_WANT_READ:
            LOG_DEBUG(Service_SSL, "{} => SSL_ERROR_WANT_READ", what);
            return ResultWouldBlock;
        case SSL_ERROR_WANT_WRITE:
            LOG_DEBUG(Service_SSL, "{} => SSL_ERROR_WANT_WRITE", what);
            return ResultWouldBlock;
        default:
            if (ssl_err == SSL_ERROR_SYSCALL && got_read_eof) {
                LOG_DEBUG(Service_SSL, "{} => SSL_ERROR_SYSCALL because server hung up", what);
                *actual = 0;
                return ResultSuccess;
            }
            LOG_ERROR(Service_SSL, "{} => other SSL_get_error return value {}", what, ssl_err);
            return ResultInternalError;
        }
    }

    Result GetServerCerts(std::vector<std::vector<u8>>* out_certs) override {
        STACK_OF(X509)* chain = SSL_get_peer_cert_chain(ssl);
        if (!chain) {
            LOG_ERROR(Service_SSL, "SSL_get_peer_cert_chain returned nullptr");
            return ResultInternalError;
        }
        int count = sk_X509_num(chain);
        ASSERT(count >= 0);
        for (int i = 0; i < count; i++) {
            X509* x509 = sk_X509_value(chain, i);
            ASSERT_OR_EXECUTE(x509 != nullptr, { continue; });
            unsigned char* buf = nullptr;
            int len = i2d_X509(x509, &buf);
            ASSERT_OR_EXECUTE(len >= 0 && buf, { continue; });
            out_certs->emplace_back(buf, buf + len);
            OPENSSL_free(buf);
        }
        return ResultSuccess;
    }

    ~SSLConnectionBackendOpenSSL() {
        if (!nextendo_host.empty()) {
            LOG_INFO(Service_SSL, "Nextendo: TLS to {} closed, sent {} bytes, received {}{}",
                     nextendo_host, bytes_written, bytes_read,
                     got_read_eof ? ", server hung up" : "");
        }
        // this is null-tolerant:
        SSL_free(ssl);
    }

    void LogNextendoHandshake(int ret) {
        if (ret == 1) {
            const unsigned char* alpn = nullptr;
            unsigned int alpn_len = 0;
            SSL_get0_alpn_selected(ssl, &alpn, &alpn_len);
            LOG_INFO(Service_SSL, "Nextendo: TLS to {} established ({}, ALPN '{}')",
                     nextendo_host, SSL_get_version(ssl),
                     std::string_view{reinterpret_cast<const char*>(alpn), alpn_len});
            return;
        }
        const int err = SSL_get_error(ssl, ret);
        if (err != SSL_ERROR_WANT_READ && err != SSL_ERROR_WANT_WRITE) {
            LOG_WARNING(Service_SSL, "Nextendo: TLS handshake with {} failed (SSL error {})",
                        nextendo_host, err);
        }
    }

    static void KeyLogCallback(const SSL* ssl, const char* line) {
        std::string str(line);
        str.push_back('\n');
        // Do this in a single WriteString for atomicity if multiple instances
        // are running on different threads (though that can't currently
        // happen).
        if (key_log_file.WriteString(str) != str.size() || !key_log_file.Flush()) {
            LOG_CRITICAL(Service_SSL, "Failed to write to SSLKEYLOGFILE");
        }
        LOG_DEBUG(Service_SSL, "Wrote to SSLKEYLOGFILE: {}", line);
    }

    static int WriteCallback(BIO* bio, const char* buf, size_t len, size_t* actual_p) {
        auto self = static_cast<SSLConnectionBackendOpenSSL*>(BIO_get_data(bio));
        ASSERT_OR_EXECUTE_MSG(
            self->socket, { return 0; }, "OpenSSL asked to send but we have no socket");
        BIO_clear_retry_flags(bio);
        auto [actual, err] = self->socket->Send({reinterpret_cast<const u8*>(buf), len}, 0);
        switch (err) {
        case Network::Errno::SUCCESS:
            *actual_p = actual;
            return 1;
        case Network::Errno::AGAIN:
            BIO_set_flags(bio, BIO_FLAGS_WRITE | BIO_FLAGS_SHOULD_RETRY);
            return 0;
        default:
            LOG_ERROR(Service_SSL, "Socket send returned Network::Errno {}", err);
            return -1;
        }
    }

    static int ReadCallback(BIO* bio, char* buf, size_t len, size_t* actual_p) {
        auto self = static_cast<SSLConnectionBackendOpenSSL*>(BIO_get_data(bio));
        ASSERT_OR_EXECUTE_MSG(
            self->socket, { return 0; }, "OpenSSL asked to recv but we have no socket");
        BIO_clear_retry_flags(bio);
        auto [actual, err] = self->socket->Recv(0, {reinterpret_cast<u8*>(buf), len});
        switch (err) {
        case Network::Errno::SUCCESS:
            *actual_p = actual;
            if (actual == 0) {
                self->got_read_eof = true;
            }
            return actual ? 1 : 0;
        case Network::Errno::AGAIN:
            BIO_set_flags(bio, BIO_FLAGS_READ | BIO_FLAGS_SHOULD_RETRY);
            return 0;
        default:
            LOG_ERROR(Service_SSL, "Socket recv returned Network::Errno {}", err);
            return -1;
        }
    }

    static long CtrlCallback(BIO* bio, int cmd, long l_arg, void* p_arg) {
        switch (cmd) {
        case BIO_CTRL_FLUSH:
            // Nothing to flush.
            return 1;
        case BIO_CTRL_PUSH:
        case BIO_CTRL_POP:
#ifdef BIO_CTRL_GET_KTLS_SEND
        case BIO_CTRL_GET_KTLS_SEND:
        case BIO_CTRL_GET_KTLS_RECV:
#endif
            // We don't support these operations, but don't bother logging them
            // as they're nothing unusual.
            return 0;
        default:
            LOG_DEBUG(Service_SSL, "OpenSSL BIO got ctrl({}, {}, {})", cmd, l_arg, p_arg);
            return 0;
        }
    }

    std::string PeerAddress() const {
        if (!socket) {
            return {};
        }
        const auto [peer, err] = socket->GetPeerName();
        return err == Network::Errno::SUCCESS ? Network::IPv4AddressToString(peer.ip)
                                              : std::string{};
    }

    std::string RedirectedHostOfPeer() const {
        const std::string address = PeerAddress();
        return address.empty() ? std::string{} : Service::Sockets::GetRedirectedHostForIp(address);
    }

    // Nextendo Network connection: make sure the SNI names the Nintendo host, and offer
    // HTTP/1.1 only - NEX runs over a WebSocket upgrade that HTTP/2 would break. NPLN titles
    // (gRPC) need the HTTP/2 they asked for. After citron-nextendo (Copyright 2026 citron
    // Emulator Project).
    void PrepareNextendoHandshake(const std::string& redirected_host) {
        const char* sni = SSL_get_servername(ssl, TLSEXT_NAMETYPE_host_name);
        if (sni == nullptr) {
            SSL_set_tlsext_host_name(ssl, redirected_host.c_str());
            sni = redirected_host.c_str();
        }
        const std::string host = Common::ToLower(std::string{sni});
        const bool npln = host.find("npln") != std::string::npos ||
                          host.find("gs.nintendo.net") != std::string::npos;

        std::vector<u8> wire;
        if (npln) {
            for (size_t pos = 0; pos < requested_alpn.size();) {
                const u8 len = requested_alpn[pos];
                if (len == 0 || pos + 1 + len > requested_alpn.size()) {
                    break;
                }
                const std::string_view proto{
                    reinterpret_cast<const char*>(requested_alpn.data() + pos + 1), len};
                if (proto == "h2" || proto == "http/1.1") {
                    wire.insert(wire.end(), requested_alpn.begin() + pos,
                                requested_alpn.begin() + pos + 1 + len);
                }
                pos += 1 + len;
            }
        }
        if (wire.empty()) {
            static constexpr std::string_view http11{"\x08http/1.1"};
            wire.assign(http11.begin(), http11.end());
        }
        SSL_set_alpn_protos(ssl, wire.data(), static_cast<unsigned int>(wire.size()));
    }

    SSL* ssl = nullptr;
    BIO* bio = nullptr;
    bool got_read_eof = false;
    bool skip_cert_verification = false;
    std::vector<u8> requested_alpn;
    std::string nextendo_host; ///< Set when this connection goes to a Nextendo server.
    size_t bytes_read = 0;
    size_t bytes_written = 0;

    std::shared_ptr<Network::SocketBase> socket;
};

Result CreateSSLConnectionBackend(std::unique_ptr<SSLConnectionBackend>* out_backend) {
    auto conn = std::make_unique<SSLConnectionBackendOpenSSL>();

    R_TRY(conn->Init());

    *out_backend = std::move(conn);
    return ResultSuccess;
}

namespace {

Result CheckOpenSSLErrors() {
    unsigned long rc;
    const char* file;
    int line;
    const char* func;
    const char* data;
    int flags;
#if OPENSSL_VERSION_NUMBER >= 0x30000000L
    while ((rc = ERR_get_error_all(&file, &line, &func, &data, &flags)))
#else
    // Can't get function names from OpenSSL on this version, so use mine:
    func = __func__;
    while ((rc = ERR_get_error_line_data(&file, &line, &data, &flags)))
#endif
    {
        std::string msg;
        msg.resize(1024, '\0');
        ERR_error_string_n(rc, msg.data(), msg.size());
        msg.resize(strlen(msg.data()), '\0');
        if (flags & ERR_TXT_STRING) {
            msg.append(" | ");
            msg.append(data);
        }
        Common::Log::FmtLogMessage(Common::Log::Class::Service_SSL, Common::Log::Level::Error,
                                   file, line, func, "OpenSSL: {}",
                                   msg);
    }
    return ResultInternalError;
}

void OneTimeInit() {
    ssl_ctx = SSL_CTX_new(TLS_client_method());
    if (!ssl_ctx) {
        LOG_ERROR(Service_SSL, "SSL_CTX_new failed");
        CheckOpenSSLErrors();
        return;
    }

    SSL_CTX_set_verify(ssl_ctx, SSL_VERIFY_PEER, nullptr);

    if (!SSL_CTX_set_default_verify_paths(ssl_ctx)) {
        LOG_ERROR(Service_SSL, "SSL_CTX_set_default_verify_paths failed");
        CheckOpenSSLErrors();
        return;
    }

    OneTimeInitLogFile();

    if (!OneTimeInitBIO()) {
        return;
    }

    one_time_init_success = true;
}

void OneTimeInitLogFile() {
    const char* logfile = getenv("SSLKEYLOGFILE");
    if (logfile) {
        key_log_file.Open(logfile, FileAccessMode::Append, FileType::TextFile,
                          FileShareFlag::ShareWriteOnly);
        if (key_log_file.IsOpen()) {
            SSL_CTX_set_keylog_callback(ssl_ctx, &SSLConnectionBackendOpenSSL::KeyLogCallback);
        } else {
            LOG_CRITICAL(Service_SSL,
                         "SSLKEYLOGFILE was set but file could not be opened; not logging keys!");
        }
    }
}

bool OneTimeInitBIO() {
    bio_meth =
        BIO_meth_new(BIO_get_new_index() | BIO_TYPE_SOURCE_SINK, "SSLConnectionBackendOpenSSL");
    if (!bio_meth ||
        !BIO_meth_set_write_ex(bio_meth, &SSLConnectionBackendOpenSSL::WriteCallback) ||
        !BIO_meth_set_read_ex(bio_meth, &SSLConnectionBackendOpenSSL::ReadCallback) ||
        !BIO_meth_set_ctrl(bio_meth, &SSLConnectionBackendOpenSSL::CtrlCallback)) {
        LOG_ERROR(Service_SSL, "Failed to create BIO_METHOD");
        return false;
    }
    return true;
}

} // namespace

} // namespace Service::SSL
