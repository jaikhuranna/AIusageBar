import Network
import SwiftUI

/// Pair with the AIusageBar bridge the same way the watch and the Android
/// widgets do: find it on the LAN (or type its address), enter the 6-digit
/// code from the tray, and keep the token and public URL it hands back.
struct PairingView: View {
    @State private var target: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Pair with your desktop").font(.title2.bold())
            Text("On the desktop, choose Pair a device… in the AIusageBar tray menu (or run aiusagebar -pair).")
                .font(.callout)
            if let target {
                CodeEntry(base: target) { self.target = nil }
            } else {
                ChooseBridge { target = $0 }
            }
        }
    }
}

private struct ChooseBridge: View {
    let onChosen: (String) -> Void
    @StateObject private var browser = BridgeBrowser()
    @State private var address = ""
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("On this Wi-Fi").font(.headline)
            if browser.found.isEmpty { Text("Searching…").font(.footnote).foregroundStyle(.secondary) }
            ForEach(browser.found) { f in
                Button { onChosen(f.url) } label: {
                    Text(f.name.replacingOccurrences(of: "AIusageBar on ", with: "")).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
            }
            Button("Search again") { browser.start() }

            Text("Or by address").font(.headline)
            TextField("usage.example.com", text: $address)
                .textFieldStyle(.roundedBorder)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onChange(of: address) { error = nil }
            if let error { Text(error).font(.footnote).foregroundStyle(.red) }
            Button {
                switch normalizeAddress(address) {
                case let .success(url): onChosen(url)
                case let .failure(e): error = e.message
                }
            } label: {
                Text("Next").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
        .onAppear { browser.start() }
        .onDisappear { browser.stop() }
    }
}

private struct CodeEntry: View {
    let base: String
    let onBack: () -> Void
    @EnvironmentObject private var model: AppModel
    @State private var code = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(base.components(separatedBy: "://").last ?? base).font(.footnote).foregroundStyle(.secondary)
            TextField("Pairing code", text: $code)
                .textFieldStyle(.roundedBorder)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .onChange(of: code) {
                    let digits = String(code.filter(\.isNumber).prefix(6))
                    if digits != code { code = digits }
                    error = nil
                }
            if let error { Text(error).font(.footnote).foregroundStyle(.red) }
            Button {
                busy = true
                Task {
                    do {
                        try await model.pair(base: base, code: code)
                    } catch {
                        self.error = error.localizedDescription
                    }
                    busy = false
                }
            } label: {
                Text(busy ? "Pairing…" : "Pair").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(code.count != 6 || busy)
            Button("Back", action: onBack)
        }
    }
}

/// Bridges advertising `_aiusage._tcp` on the LAN. IPv4 only, to keep URLs simple.
@MainActor
private final class BridgeBrowser: ObservableObject {
    struct Found: Identifiable {
        let name: String
        let url: String
        var id: String { name }
    }

    @Published private(set) var found: [Found] = []
    private var browser: NWBrowser?
    private var resolving: [String: NWConnection] = [:]

    func start() {
        stop()
        found = []
        let b = NWBrowser(for: .bonjour(type: "_aiusage._tcp", domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            Task { @MainActor in self?.update(results) }
        }
        b.start(queue: .main)
        browser = b
    }

    func stop() {
        browser?.cancel()
        browser = nil
        resolving.values.forEach { $0.cancel() }
        resolving = [:]
    }

    private func update(_ results: Set<NWBrowser.Result>) {
        var names = Set<String>()
        for r in results {
            guard case let .service(name, _, _, _) = r.endpoint else { continue }
            names.insert(name)
            if !found.contains(where: { $0.name == name }) { resolve(name, r.endpoint) }
        }
        found.removeAll { !names.contains($0.name) }
    }

    /// Network.framework resolves a service by connecting to it; the address
    /// it connected to is the one to use.
    private func resolve(_ name: String, _ endpoint: NWEndpoint) {
        guard resolving[name] == nil else { return }
        let params = NWParameters.tcp
        (params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options)?.version = .v4
        let c = NWConnection(to: endpoint, using: params)
        resolving[name] = c
        c.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in self?.resolved(name, c, state) }
        }
        c.start(queue: .main)
    }

    private func resolved(_ name: String, _ c: NWConnection, _ state: NWConnection.State) {
        switch state {
        case .ready:
            if case let .hostPort(host, port)? = c.currentPath?.remoteEndpoint, case let .ipv4(addr) = host {
                let ip = addr.rawValue.map(String.init).joined(separator: ".")
                if !found.contains(where: { $0.name == name }) { found.append(Found(name: name, url: "http://\(ip):\(port.rawValue)")) }
            }
        case .failed, .waiting, .cancelled:
            break
        default:
            return
        }
        c.stateUpdateHandler = nil
        c.cancel()
        resolving[name] = nil
    }
}
