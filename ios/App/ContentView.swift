import SwiftUI

/// Each family as the detail behind the widgets, like Android's MainActivity:
/// a Session card and a Weekly card, each the share of all the family's
/// accounts left, what comes back at the next reset, the pace, a segment per
/// account and the accounts' own numbers. Pairing and the widgets' settings sit
/// behind the header's "•••". No widget faces in the app; a widget tap opens
/// the card (`CardView`) over it.
struct ContentView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var phase
    @State private var settings = false

    var body: some View {
        // Re-read once a minute too, so countdowns and ages move.
        TimelineView(.everyMinute) { ctx in
            let _ = model.version
            let now = ctx.date
            let look = model.look(at: now)
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Header(settings: $settings)
                    if look.mode == .unpaired && !Fixtures.enabled {
                        Panel {
                            if model.store.cache(model.store.selected).lastError == .unpaired {
                                Text("The desktop no longer knows this phone. Pair again.").font(.system(size: 14)).foregroundStyle(Palette.orange)
                            }
                            PairingView()
                        }
                    } else {
                        ForEach(model.views(at: now), id: \.family) { view in
                            Spacer().frame(height: 10)
                            FamilyHeader(view: view, size: 14, trailing: ageText(view, now: now)).padding(.horizontal, 8)
                            if view.known.isEmpty {
                                Panel {
                                    Text(view.error ?? "No numbers from the desktop yet.")
                                        .font(.system(size: 14)).lineSpacing(4).foregroundStyle(fg(0.6))
                                }
                            } else {
                                ForEach(Limit.allCases, id: \.self) { MeterCard(view: view, limit: $0, now: now) }
                            }
                        }
                        Spacer().frame(height: 12)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
            .background(Palette.page.ignoresSafeArea())
            .tint(Palette.accent(model.store.selected))
        }
        .sheet(isPresented: $settings) { SettingsSheet().environmentObject(model) }
        .sheet(isPresented: $model.showCard) { CardView().environmentObject(model) }
        // Opening the app is a refresh (throttled to one a minute).
        .onChange(of: phase, initial: true) { _, p in
            if p == .active { Task { await model.refresh(.manual) } }
        }
    }
}

/// The title, and two quiet round buttons: refresh, and the settings sheet.
private struct Header: View {
    @EnvironmentObject private var model: AppModel
    @Binding var settings: Bool

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                CapsLabel("AI USAGE", alpha: 0.95, size: 15)
                CapsLabel(model.store.host.map { "FROM \($0.uppercased())" } ?? (Fixtures.enabled ? "FIXTURE" : "NOT PAIRED"), alpha: 0.4, size: 10)
            }
            Spacer()
            if model.store.paired {
                RoundButton(description: "Refresh", enabled: !model.busy) {
                    Task { await model.refresh(.force) }
                } icon: { c in ResetArrow(color: c).frame(width: 20, height: 20) }
            }
            RoundButton(description: "Settings") { settings = true } icon: { c in
                // Three dots, the dot-matrix's smallest word.
                HStack(spacing: 2.2) { ForEach(0..<3, id: \.self) { _ in Circle().fill(c).frame(width: 3.8, height: 3.8) } }
            }
        }
        .padding(.leading, 8)
        .padding(.top, 8)
    }
}

private struct RoundButton<Icon: View>: View {
    let description: String
    var enabled = true
    let action: () -> Void
    @ViewBuilder let icon: (Color) -> Icon

    var body: some View {
        Button(action: action) {
            icon(fg(enabled ? 0.85 : 0.3))
                .frame(width: 38, height: 38)
                .background(Palette.background, in: Circle())
                .frame(width: 48, height: 48)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(description)
    }
}

struct Panel<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 16) { content }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.background, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
    }
}

/// Everything that isn't usage: which provider the widgets draw, which widgets
/// there are (iOS apps can't place one, so it says how), and the paired desktop,
/// with Unpair behind a confirmation.
private struct SettingsSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirm = false

    private static let widgets = [
        ("AI Usage orbit", "Small"),
        ("AI Usage dash", "Medium"),
        ("AI Usage threads", "Medium · Lock Screen"),
        ("AI Usage pool", "Lock Screen"),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                CapsLabel("WIDGETS SHOW", alpha: 0.9)
                Text("The dash draws the one you pick; the pool and the threads' figures pool its family. The orbit and the threads show every account.")
                    .font(.system(size: 13)).lineSpacing(3).foregroundStyle(fg(0.55))
                HStack(spacing: 8) {
                    ForEach(UsageProvider.allCases, id: \.self) { p in
                        let selected = p == model.store.selected
                        let accent = Palette.accent(p)
                        Button { Task { await model.selectProvider(p) } } label: {
                            CapsLabel(p.title.uppercased(), alpha: selected ? 1 : 0.7, size: 11, color: selected ? accent : .primary)
                                .padding(.horizontal, 14).padding(.vertical, 10)
                                .frame(minHeight: 40)
                                .background(selected ? accent.opacity(0.15) : .clear, in: Capsule())
                                .overlay(Capsule().strokeBorder(selected ? accent : fg(0.2), lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(selected ? .isSelected : [])
                    }
                }
                Divider()
                CapsLabel("ADD TO HOME SCREEN", alpha: 0.9)
                Text("Touch and hold the Home Screen or Lock Screen, tap Edit, then Add Widget, and search for AI Usage.")
                    .font(.system(size: 13)).lineSpacing(3).foregroundStyle(fg(0.55))
                VStack(spacing: 0) {
                    ForEach(Self.widgets, id: \.0) { name, sizes in
                        HStack {
                            Text(name).font(.system(size: 15)).foregroundStyle(fg(0.85))
                            Spacer()
                            CapsLabel(sizes.uppercased(), alpha: 0.45, size: 10)
                        }
                        .frame(minHeight: 44)
                    }
                }
                if model.store.paired {
                    Divider()
                    HStack {
                        CapsLabel("PAIRED WITH \((model.store.host ?? "desktop").uppercased())", alpha: 0.5, size: 11)
                        Spacer()
                        Button { confirm = true } label: {
                            CapsLabel("UNPAIR", size: 11, color: Palette.orange).padding(.horizontal, 12).frame(minHeight: 44)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 28)
            .padding(.bottom, 16)
        }
        .presentationDetents([.medium, .large])
        .presentationBackground(Palette.background)
        .presentationCornerRadius(32)
        .alert("Unpair this phone?", isPresented: $confirm) {
            Button("Unpair", role: .destructive) {
                model.unpair()
                dismiss()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The widgets stop updating until you pair again with a new code from the desktop.")
        }
    }
}

private struct Divider: View {
    var body: some View { Rectangle().fill(fg(0.08)).frame(height: 1) }
}
