import SwiftUI

struct SpectrumBarsView: View {
    let values: [Float]
    var color: Color = .cyan
    var title: String = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if !title.isEmpty {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            GeometryReader { geo in
                let n = max(values.count, 1)
                let barW = max(geo.size.width / CGFloat(n) - 2, 1)
                HStack(alignment: .bottom, spacing: 2) {
                    ForEach(0..<n, id: \.self) { i in
                        let h = CGFloat(values[i]) * geo.size.height
                        RoundedRectangle(cornerRadius: 2)
                            .fill(color.opacity(0.85))
                            .frame(width: barW, height: max(h, 2))
                    }
                }
                .frame(maxHeight: .infinity, alignment: .bottom)
            }
            .frame(height: 72)
        }
    }
}

struct MetricPill: View {
    let label: String
    let value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.subheadline.monospacedDigit().weight(.semibold))
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}
