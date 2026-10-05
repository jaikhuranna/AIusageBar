import Foundation

/// The AIusageBar bridge's HTTP API: `/usage` and `/pair`. The widgets render
/// what they fetch and never derive alerts, so there's no `/events`.
enum Bridge {
    enum Usage {
        case fresh(body: String, etag: String?)
        case notModified
        case unauthorized
        case failed(String)
    }

    struct Paired {
        let token: String
        let publicUrl: String?
        let host: String
    }

    struct PairError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /// No URL cache: the ETag round trip is ours, and a cached 200 would hide the 304.
    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.urlCache = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.timeoutIntervalForRequest = 15
        return URLSession(configuration: c)
    }()

    static func usage(base: String, token: String, etag: String?, provider: UsageProvider = .claude) async -> Usage {
        guard let url = URL(string: base + "/usage?provider=\(provider.rawValue)") else { return .failed("bad address") }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let etag { req.setValue(etag, forHTTPHeaderField: "If-None-Match") }
        do {
            let (data, resp) = try await session.data(for: req)
            let http = resp as? HTTPURLResponse
            switch http?.statusCode ?? 0 {
            case 200: return .fresh(body: String(decoding: data, as: UTF8.self), etag: http?.value(forHTTPHeaderField: "ETag"))
            case 304: return .notModified
            case 401: return .unauthorized
            case let code: return .failed("HTTP \(code)")
            }
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    static func pair(base: String, code: String, name: String) async throws -> Paired {
        guard let url = URL(string: base + "/pair") else { throw PairError(message: "That doesn't look like an address") }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: ["code": code, "name": name])
        let data: Data
        let resp: URLResponse
        do {
            let r = try await session.data(for: req)
            data = r.0
            resp = r.1
        } catch {
            throw PairError(message: "Can't reach the bridge at \(base)")
        }
        switch (resp as? HTTPURLResponse)?.statusCode ?? 0 {
        case 200:
            let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
            guard let token = o["token"] as? String else { throw PairError(message: "The bridge sent no token") }
            let pub = o["public_url"] as? String
            return Paired(token: token, publicUrl: pub?.hasPrefix("https://") == true ? pub : nil, host: o["host"] as? String ?? "")
        case 403:
            throw PairError(message: "Wrong or expired code. Get a new one.")
        case let code:
            throw PairError(message: "The bridge said HTTP \(code)")
        }
    }
}
