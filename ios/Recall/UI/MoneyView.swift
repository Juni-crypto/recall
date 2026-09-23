import Charts
import RecallCore
import SwiftUI

struct MoneyView: View {
    enum Mode: String, CaseIterable { case spending = "Spending", received = "Received", invested = "Invested" }
    enum Period: String, CaseIterable { case day = "Day", week = "Week", month = "Month", year = "Year", all = "All time" }

    @Environment(AppModel.self) private var model
    @State private var mode: Mode = .spending
    @State private var period: Period = .month
    @State private var offset = 0
    @State private var party: String?
    @State private var category: String?

    var body: some View {
        let _ = model.version
        let (start, end, label) = range(offset)
        let (pStart, pEnd, pLabel) = range(offset - 1)
        let store = model.store
        let (spent, received) = store.moneyTotals(from: start, to: end)
        let (prevSpent, prevReceived) = store.moneyTotals(from: pStart, to: pEnd)

        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                ScreenTitle(title: "Money")
                Picker("", selection: $mode) { ForEach(Mode.allCases, id: \.self) { Text($0.rawValue) } }.pickerStyle(.segmented)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) { ForEach(Period.allCases, id: \.self) { p in Pill(text: p.rawValue, selected: period == p) { period = p; offset = 0 } } }
                }
                if period != .all {
                    HStack {
                        Button { offset -= 1 } label: { Image(systemName: "chevron.left") }
                        Spacer()
                        Text(label).font(Type.body(15, weight: .semibold)).foregroundStyle(RC.text)
                        Spacer()
                        Button { offset += 1 } label: { Image(systemName: "chevron.right") }.disabled(offset >= 0).opacity(offset >= 0 ? 0.3 : 1)
                    }
                    .foregroundStyle(RC.text)
                }

                switch mode {
                case .spending:
                    totalCard(title: "Spent", value: spent, previous: period == .all ? nil : prevSpent, prevLabel: pLabel, color: RC.text) {
                        HStack {
                            stat("Received", Fmt.rupees(received), RC.money)
                            stat("Net", (received - spent >= 0 ? "+" : "") + Fmt.rupees(received - spent), received >= spent ? RC.money : RC.urgent)
                            stat("To savings", Fmt.rupees(store.savingsTotal(from: start, to: end)), RC.invest)
                        }
                    }
                    if period == .month || period == .week { compareChart(start: start, pStart: pStart, direction: "out", label: pLabel) }
                    categories(start: start, end: end)
                    txnList(store.txns(from: start, to: end, direction: "out", category: category).filter { $0.category != "Self transfer" && $0.category != "Savings" })
                case .received:
                    totalCard(title: "Received", value: received, previous: period == .all ? nil : prevReceived, prevLabel: pLabel, color: RC.money) {
                        let from = store.partyTotals(from: start, to: end, direction: "in", limit: 50)
                        HStack {
                            stat("From", "\(from.count) sources", RC.text)
                            stat("Payments in", "\(from.reduce(0) { $0 + $1.count })", RC.text)
                        }
                    }
                    if period == .month || period == .week { compareChart(start: start, pStart: pStart, direction: "in", label: pLabel) }
                    parties(start: start, end: end)
                    txnList(store.txns(from: start, to: end, direction: "in").filter { $0.category != "Self transfer" })
                case .invested:
                    invested(start: start, end: end)
                }
            }
            .padding(16)
        }
        .sheet(item: Binding(get: { party.map(PartyID.init) }, set: { party = $0?.id })) { p in PartyDetail(name: p.id) }
    }

    // MARK: - Pieces

    private func stat(_ label: String, _ value: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(Type.body(11)).foregroundStyle(RC.muted)
            Text(value).font(Type.mono(13)).foregroundStyle(color)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func totalCard<Extra: View>(title: String, value: Int64, previous: Int64?, prevLabel: String, color: Color, @ViewBuilder extra: () -> Extra) -> some View {
        Card {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(Type.body(12)).foregroundStyle(RC.muted)
                    Text(Fmt.rupees(value)).font(Type.display(34)).foregroundStyle(color)
                }
                Spacer()
                if let previous, previous > 0 {
                    let pct = Int(((Double(value) - Double(previous)) / Double(previous) * 100).rounded())
                    Tag(text: "\(pct >= 0 ? "↑" : "↓") \(abs(pct))% vs \(prevLabel)", color: RC.muted)
                }
            }
            extra()
        }
    }

    private struct Point: Identifiable { let id = UUID(); let day: Int; let value: Double; let series: String }

    private func compareChart(start: Int64, pStart: Int64, direction: String, label: String) -> some View {
        let days = period == .week ? 7 : 31
        let now = model.store.dailySpend(from: start, days: days, direction: direction)
        let before = model.store.dailySpend(from: pStart, days: days, direction: direction)
        let today = Calendar.current.dateComponents([.day], from: Clock.date(start), to: Date()).day ?? days
        var points: [Point] = []
        var a: Int64 = 0, b: Int64 = 0
        for d in 0..<days {
            b += before[d]
            points.append(Point(day: d + 1, value: Double(b) / 100, series: label))
            if offset < 0 || d <= today {
                a += now[d]
                points.append(Point(day: d + 1, value: Double(a) / 100, series: "So far"))
            }
        }
        return Card {
            Label2(text: "\(direction == "out" ? "Spent" : "Received") · this vs \(label)")
            Chart(points) { p in
                LineMark(x: .value("Day", p.day), y: .value("₹", p.value))
                    .foregroundStyle(by: .value("Series", p.series))
                    .lineStyle(StrokeStyle(lineWidth: p.series == "So far" ? 2.5 : 1.5, dash: p.series == "So far" ? [] : [4, 3]))
            }
            .chartForegroundStyleScale(["So far": RC.accent, label: RC.dim])
            .chartYAxis { AxisMarks(position: .trailing) { _ in AxisGridLine().foregroundStyle(RC.line); AxisValueLabel().font(Type.mono(9)).foregroundStyle(RC.dim) } }
            .chartXAxis { AxisMarks(values: .automatic(desiredCount: 5)) { _ in AxisValueLabel().font(Type.mono(9)).foregroundStyle(RC.dim) } }
            .chartLegend(position: .bottom)
            .frame(height: 170)
        }
    }

    private func categories(start: Int64, end: Int64) -> some View {
        let cats = model.store.categoryTotals(from: start, to: end).filter { $0.category != "Savings" }
        let total = max(1, cats.reduce(0) { $0 + $1.paise })
        return Card {
            Label2(text: "Where it went", trailing: category == nil ? "Tap to filter" : "Clear")
                .onTapGesture { category = nil }
            if cats.isEmpty { Text("No payments in this period.").font(Type.body(13)).foregroundStyle(RC.muted) }
            GeometryReader { geo in
                HStack(spacing: 2) {
                    ForEach(cats) { c in
                        RC.color(for: c.category).frame(width: max(2, geo.size.width * CGFloat(c.paise) / CGFloat(total)))
                    }
                }
            }
            .frame(height: 8).clipShape(Capsule())
            ForEach(cats) { c in
                Button { category = category == c.category ? nil : c.category } label: {
                    HStack {
                        Circle().fill(RC.color(for: c.category)).frame(width: 9, height: 9)
                        Text(c.category).font(Type.body(14)).foregroundStyle(category == nil || category == c.category ? RC.text : RC.dim)
                        Spacer()
                        Text("\(c.count)×").font(Type.mono(11)).foregroundStyle(RC.dim)
                        Text(Fmt.rupees(c.paise)).font(Type.mono(13)).foregroundStyle(RC.text)
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func parties(start: Int64, end: Int64) -> some View {
        let from = model.store.partyTotals(from: start, to: end, direction: "in", limit: 12)
        return Card {
            Label2(text: "From who", trailing: "Tap for all payments")
            if from.isEmpty { Text("Nothing received in this period.").font(Type.body(13)).foregroundStyle(RC.muted) }
            ForEach(from) { p in
                Button { party = p.name } label: {
                    HStack {
                        Text(p.name).font(Type.body(14)).foregroundStyle(RC.text).lineLimit(1)
                        Spacer()
                        Text("\(p.count)×").font(Type.mono(11)).foregroundStyle(RC.dim)
                        Text(Fmt.rupees(p.paise)).font(Type.mono(13)).foregroundStyle(RC.money)
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func invested(start: Int64, end: Int64) -> some View {
        let txns = model.store.txns(from: start, to: end, category: "Savings")
        let putIn = txns.filter(\.isOut).reduce(Int64(0)) { $0 + $1.amountPaise }
        let cameBack = txns.filter { !$0.isOut }.reduce(Int64(0)) { $0 + $1.amountPaise }
        var byType: [String: (Int64, Int64)] = [:]
        for t in txns {
            let k = Categories.investmentType(t.raw, merchant: t.merchant)
            var v = byType[k] ?? (0, 0)
            if t.isOut { v.0 += t.amountPaise } else { v.1 += t.amountPaise }
            byType[k] = v
        }
        return VStack(spacing: 12) {
            Card {
                Text("Put into savings and investments").font(Type.body(12)).foregroundStyle(RC.muted)
                Text(Fmt.rupees(putIn)).font(Type.display(34)).foregroundStyle(RC.invest)
                HStack {
                    stat("Came back", Fmt.rupees(cameBack), RC.money)
                    stat("Net flow", Fmt.rupees(putIn - cameBack), RC.text)
                }
                Text("From your bank messages only. It isn't the market value of what you hold.").font(Type.body(11)).foregroundStyle(RC.dim)
            }
            Card {
                Label2(text: "By type", trailing: "Put in · came back")
                if byType.isEmpty { Text("No FDs, RDs or SIPs in this period.").font(Type.body(13)).foregroundStyle(RC.muted) }
                ForEach(byType.sorted { $0.value.0 > $1.value.0 }, id: \.key) { k, v in
                    HStack {
                        Text(k).font(Type.body(14)).foregroundStyle(RC.text)
                        Spacer()
                        Text("\(Fmt.rupees(v.0)) · \(Fmt.rupees(v.1))").font(Type.mono(12)).foregroundStyle(RC.muted)
                    }
                }
            }
            txnList(txns)
        }
    }

    private func txnList(_ txns: [Txn]) -> some View {
        Card {
            Label2(text: "Payments", trailing: "\(txns.count)")
            if txns.isEmpty { Text("Nothing here yet. Bank texts reach Recall through a Shortcuts automation (You → Set up Shortcuts).").font(Type.body(13)).foregroundStyle(RC.muted) }
            ForEach(txns.prefix(80)) { t in
                Button { if let m = t.merchant { party = m } } label: { TxnRow(txn: t) }
                    .buttonStyle(.plain)
                    .contextMenu {
                        if let m = t.merchant {
                            Menu("Category for \(m)") {
                                ForEach(Categories.ALL, id: \.self) { c in
                                    Button(c) { model.store.setMerchantCategory(m, category: c, by: "user") }
                                }
                            }
                        }
                    }
            }
        }
    }

    // MARK: - Periods

    private func range(_ off: Int) -> (Int64, Int64, String) {
        let cal = Calendar.current
        let now = Date()
        switch period {
        case .day:
            let d = cal.date(byAdding: .day, value: off, to: cal.startOfDay(for: now))!
            let label = off == 0 ? "Today" : off == -1 ? "Yesterday" : d.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))
            return (Clock.ms(d), Clock.ms(cal.date(byAdding: .day, value: 1, to: d)!), label)
        case .week:
            let s = cal.dateInterval(of: .weekOfYear, for: cal.date(byAdding: .weekOfYear, value: off, to: now)!)!
            return (Clock.ms(s.start), Clock.ms(s.end), off == 0 ? "This week" : off == -1 ? "Last week" : s.start.formatted(.dateTime.day().month(.abbreviated)))
        case .month:
            let s = cal.dateInterval(of: .month, for: cal.date(byAdding: .month, value: off, to: now)!)!
            return (Clock.ms(s.start), Clock.ms(s.end), s.start.formatted(.dateTime.month(.wide).year()))
        case .year:
            let s = cal.dateInterval(of: .year, for: cal.date(byAdding: .year, value: off, to: now)!)!
            return (Clock.ms(s.start), Clock.ms(s.end), s.start.formatted(.dateTime.year()))
        case .all:
            return (0, Clock.now + Clock.day, "All time")
        }
    }
}

private struct PartyID: Identifiable { let id: String }

struct TxnRow: View {
    let txn: Txn
    var body: some View {
        HStack(spacing: 10) {
            Circle().fill(RC.color(for: txn.category)).frame(width: 9, height: 9)
            VStack(alignment: .leading, spacing: 2) {
                Text(txn.merchant ?? (txn.isOut ? "Payment" : "Money in")).font(Type.body(14, weight: .medium)).foregroundStyle(RC.text).lineLimit(1)
                Text("\(txn.category) · \(txn.source) · \(Fmt.when(txn.at))").font(Type.mono(10)).foregroundStyle(RC.dim).lineLimit(1)
            }
            Spacer()
            Text((txn.isOut ? "-" : "+") + Fmt.rupees(txn.amountPaise)).font(Type.mono(13)).foregroundStyle(txn.isOut ? RC.text : RC.money)
        }
        .contentShape(Rectangle())
    }
}

/// Everything with one person or merchant: totals, a 12-month chart, every payment, the original message.
struct PartyDetail: View {
    let name: String
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var raw: Txn?

    var body: some View {
        let txns = model.store.txnsFor(merchant: name)
        let out = txns.filter(\.isOut).reduce(Int64(0)) { $0 + $1.amountPaise }
        let inn = txns.filter { !$0.isOut }.reduce(Int64(0)) { $0 + $1.amountPaise }
        let cal = Calendar.current
        let months: [(Date, Double)] = (0..<12).reversed().map { i in
            let m = cal.dateInterval(of: .month, for: cal.date(byAdding: .month, value: -i, to: Date())!)!
            let sum = txns.filter { Clock.date($0.at) >= m.start && Clock.date($0.at) < m.end }.reduce(Int64(0)) { $0 + $1.amountPaise }
            return (m.start, Double(sum) / 100)
        }
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    Card {
                        HStack {
                            stat("You paid", Fmt.rupees(out), RC.text)
                            stat("You got", Fmt.rupees(inn), RC.money)
                            stat("Payments", "\(txns.count)", RC.text)
                        }
                        if let first = txns.last { Text("Since \(Fmt.when(first.at))").font(Type.mono(11)).foregroundStyle(RC.dim) }
                    }
                    Card {
                        Label2(text: "Last 12 months")
                        Chart(months, id: \.0) { m in
                            BarMark(x: .value("Month", m.0, unit: .month), y: .value("₹", m.1)).foregroundStyle(RC.accent)
                        }
                        .chartXAxis { AxisMarks(values: .stride(by: .month, count: 3)) { _ in AxisValueLabel(format: .dateTime.month(.abbreviated)).font(Type.mono(9)).foregroundStyle(RC.dim) } }
                        .chartYAxis { AxisMarks(position: .trailing) { _ in AxisValueLabel().font(Type.mono(9)).foregroundStyle(RC.dim) } }
                        .frame(height: 140)
                    }
                    Card {
                        Label2(text: "Every payment", trailing: "Tap for the message")
                        ForEach(txns) { t in Button { raw = t } label: { TxnRow(txn: t) }.buttonStyle(.plain) }
                    }
                }
                .padding(16)
            }
            .background(RC.bg)
            .navigationTitle(name)
            .toolbar { Button("Done") { dismiss() } }
            .alert("Original message", isPresented: Binding(get: { raw != nil }, set: { if !$0 { raw = nil } })) {
                Button("OK", role: .cancel) {}
            } message: { Text(raw?.raw ?? "") }
        }
    }

    private func stat(_ label: String, _ value: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(Type.body(11)).foregroundStyle(RC.muted)
            Text(value).font(Type.mono(14)).foregroundStyle(color)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
