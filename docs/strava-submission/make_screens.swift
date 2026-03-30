import AppKit
import Foundation

struct SlideSpec {
    let sourcePath: String
    let outputPath: String
    let title: String
    let subtitle: String
    let eyebrow: String
    let callout: String
}

let slides: [SlideSpec] = [
    .init(
        sourcePath: "/tmp/krecords-main.png",
        outputPath: "/Users/joaopereira/Code/kRecords/docs/strava-submission/krecords-strava-01-overview.png",
        title: "kRecords on Karoo",
        subtitle: "Connect once and manage the app directly on the Karoo.",
        eyebrow: "KAROO APP OVERVIEW",
        callout: "Native Karoo extension for nearby segment PR alerts"
    ),
    .init(
        sourcePath: "/tmp/krecords-summary.png",
        outputPath: "/Users/joaopereira/Code/kRecords/docs/strava-submission/krecords-strava-02-qr-signin.png",
        title: "Fast QR Phone Sign-In",
        subtitle: "Scan on your phone, approve access, and come right back to the ride.",
        eyebrow: "PHONE AUTH FLOW",
        callout: "Built-in QR auth flow with Strava-compatible sign-in"
    ),
    .init(
        sourcePath: "/tmp/krecords-overview2.png",
        outputPath: "/Users/joaopereira/Code/kRecords/docs/strava-submission/krecords-strava-03-actions.png",
        title: "Built for Real Ride Setup",
        subtitle: "Simple actions for auth, sync, and local cache management.",
        eyebrow: "SIMPLIFIED ON-DEVICE UX",
        callout: "No backend URL or extra setup exposed to riders"
    ),
]

let canvasSize = NSSize(width: 1600, height: 1200)
let cardRect = NSRect(x: 900, y: 120, width: 540, height: 960)
let leftRect = NSRect(x: 120, y: 120, width: 720, height: 960)
let statusCropTop: CGFloat = 46

func roundedRectPath(_ rect: NSRect, radius: CGFloat) -> NSBezierPath {
    NSBezierPath(roundedRect: rect, xRadius: radius, yRadius: radius)
}

func drawParagraph(
    _ text: String,
    in rect: NSRect,
    font: NSFont,
    color: NSColor,
    lineHeight: CGFloat = 1.18
) {
    let style = NSMutableParagraphStyle()
    style.lineBreakMode = .byWordWrapping
    style.alignment = .left
    style.minimumLineHeight = font.pointSize * lineHeight
    style.maximumLineHeight = font.pointSize * lineHeight
    let attrs: [NSAttributedString.Key: Any] = [
        .font: font,
        .foregroundColor: color,
        .paragraphStyle: style,
    ]
    NSAttributedString(string: text, attributes: attrs).draw(with: rect)
}

for slide in slides {
    guard let src = NSImage(contentsOfFile: slide.sourcePath) else {
        fputs("Missing source image: \(slide.sourcePath)\n", stderr)
        continue
    }

    guard let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil,
        pixelsWide: Int(canvasSize.width),
        pixelsHigh: Int(canvasSize.height),
        bitsPerSample: 8,
        samplesPerPixel: 4,
        hasAlpha: true,
        isPlanar: false,
        colorSpaceName: .deviceRGB,
        bytesPerRow: 0,
        bitsPerPixel: 0
    ) else {
        continue
    }
    rep.size = canvasSize

    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)

    let bg = NSRect(origin: .zero, size: canvasSize)
    let gradient = NSGradient(colors: [
        NSColor(calibratedRed: 0.95, green: 0.93, blue: 0.88, alpha: 1.0),
        NSColor(calibratedRed: 0.90, green: 0.88, blue: 0.82, alpha: 1.0),
    ])!
    gradient.draw(in: bg, angle: 90)

    NSColor(calibratedRed: 0.20, green: 0.44, blue: 0.29, alpha: 0.12).setFill()
    NSBezierPath(ovalIn: NSRect(x: -140, y: 930, width: 420, height: 260)).fill()
    NSBezierPath(ovalIn: NSRect(x: 1120, y: -80, width: 420, height: 260)).fill()

    drawParagraph(
        slide.eyebrow,
        in: NSRect(x: leftRect.minX, y: 940, width: leftRect.width, height: 28),
        font: .systemFont(ofSize: 22, weight: .semibold),
        color: NSColor(calibratedRed: 0.20, green: 0.44, blue: 0.29, alpha: 1.0)
    )
    drawParagraph(
        slide.title,
        in: NSRect(x: leftRect.minX, y: 720, width: leftRect.width, height: 210),
        font: .systemFont(ofSize: 58, weight: .bold),
        color: NSColor(calibratedRed: 0.12, green: 0.15, blue: 0.12, alpha: 1.0),
        lineHeight: 1.02
    )
    drawParagraph(
        slide.subtitle,
        in: NSRect(x: leftRect.minX, y: 525, width: leftRect.width - 30, height: 170),
        font: .systemFont(ofSize: 24, weight: .regular),
        color: NSColor(calibratedRed: 0.36, green: 0.40, blue: 0.36, alpha: 1.0),
        lineHeight: 1.18
    )

    let bulletBox = NSRect(x: leftRect.minX, y: 300, width: 500, height: 180)
    let bulletPath = roundedRectPath(bulletBox, radius: 28)
    NSColor(calibratedRed: 1.0, green: 0.99, blue: 0.97, alpha: 0.80).setFill()
    bulletPath.fill()
    NSColor(calibratedRed: 0.82, green: 0.78, blue: 0.68, alpha: 0.8).setStroke()
    bulletPath.lineWidth = 2
    bulletPath.stroke()
    drawParagraph(
        slide.callout,
        in: NSRect(x: bulletBox.minX + 28, y: bulletBox.minY + 52, width: bulletBox.width - 56, height: bulletBox.height - 80),
        font: .systemFont(ofSize: 22, weight: .medium),
        color: NSColor(calibratedRed: 0.18, green: 0.22, blue: 0.18, alpha: 1.0),
        lineHeight: 1.2
    )

    let shadowRect = cardRect.offsetBy(dx: 0, dy: -18)
    let shadowPath = roundedRectPath(shadowRect, radius: 42)
    NSColor(calibratedWhite: 0.0, alpha: 0.10).setFill()
    shadowPath.fill()

    let cardPath = roundedRectPath(cardRect, radius: 42)
    NSColor.white.setFill()
    cardPath.fill()
    NSColor(calibratedRed: 0.82, green: 0.79, blue: 0.70, alpha: 1.0).setStroke()
    cardPath.lineWidth = 3
    cardPath.stroke()

    let imageInset: CGFloat = 26
    let imageRect = cardRect.insetBy(dx: imageInset, dy: imageInset)
    let innerPath = roundedRectPath(imageRect, radius: 28)
    NSGraphicsContext.current?.saveGraphicsState()
    innerPath.addClip()

    let srcSize = src.size
    let cropRect = NSRect(x: 0, y: statusCropTop, width: srcSize.width, height: srcSize.height - statusCropTop)
    let scale = max(imageRect.width / cropRect.width, imageRect.height / cropRect.height)
    let drawSize = NSSize(width: cropRect.width * scale, height: cropRect.height * scale)
    let drawRect = NSRect(
        x: imageRect.midX - drawSize.width / 2,
        y: imageRect.midY - drawSize.height / 2,
        width: drawSize.width,
        height: drawSize.height
    )
    src.draw(in: drawRect, from: cropRect, operation: .copy, fraction: 1.0)
    NSGraphicsContext.current?.restoreGraphicsState()

    drawParagraph(
        "kRecords",
        in: NSRect(x: leftRect.minX, y: 160, width: 240, height: 48),
        font: .systemFont(ofSize: 34, weight: .bold),
        color: NSColor(calibratedRed: 0.20, green: 0.44, blue: 0.29, alpha: 1.0)
    )
    drawParagraph(
        "Karoo app for local segment PR alerts",
        in: NSRect(x: leftRect.minX, y: 126, width: 420, height: 36),
        font: .systemFont(ofSize: 20, weight: .medium),
        color: NSColor(calibratedRed: 0.36, green: 0.40, blue: 0.36, alpha: 1.0)
    )

    NSGraphicsContext.restoreGraphicsState()

    guard let data = rep.representation(using: .png, properties: [:]) else {
        fputs("Failed to encode slide for \(slide.outputPath)\n", stderr)
        continue
    }
    try data.write(to: URL(fileURLWithPath: slide.outputPath))
    print(slide.outputPath)
}

func drawCenteredParagraph(
    _ text: String,
    in rect: NSRect,
    font: NSFont,
    color: NSColor,
    lineHeight: CGFloat = 1.18
) {
    let style = NSMutableParagraphStyle()
    style.lineBreakMode = .byWordWrapping
    style.alignment = .center
    style.minimumLineHeight = font.pointSize * lineHeight
    style.maximumLineHeight = font.pointSize * lineHeight
    let attrs: [NSAttributedString.Key: Any] = [
        .font: font,
        .foregroundColor: color,
        .paragraphStyle: style,
    ]
    NSAttributedString(string: text, attributes: attrs).draw(with: rect)
}

do {
    let outputPath = "/Users/joaopereira/Code/kRecords/docs/strava-submission/krecords-strava-04-pr-alert.png"
    guard let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil,
        pixelsWide: Int(canvasSize.width),
        pixelsHigh: Int(canvasSize.height),
        bitsPerSample: 8,
        samplesPerPixel: 4,
        hasAlpha: true,
        isPlanar: false,
        colorSpaceName: .deviceRGB,
        bytesPerRow: 0,
        bitsPerPixel: 0
    ) else {
        throw NSError(domain: "krecords", code: 1)
    }
    rep.size = canvasSize

    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)

    let bg = NSRect(origin: .zero, size: canvasSize)
    let bgGradient = NSGradient(colors: [
        NSColor(calibratedRed: 0.08, green: 0.16, blue: 0.13, alpha: 1.0),
        NSColor(calibratedRed: 0.10, green: 0.12, blue: 0.13, alpha: 1.0),
    ])!
    bgGradient.draw(in: bg, angle: 90)

    NSColor(calibratedRed: 0.23, green: 0.52, blue: 0.35, alpha: 0.18).setFill()
    NSBezierPath(ovalIn: NSRect(x: 1080, y: 760, width: 440, height: 300)).fill()
    NSBezierPath(ovalIn: NSRect(x: -120, y: -60, width: 520, height: 320)).fill()

    drawParagraph(
        "IN-RIDE PR ALERT",
        in: NSRect(x: 120, y: 940, width: 700, height: 28),
        font: .systemFont(ofSize: 22, weight: .semibold),
        color: NSColor(calibratedRed: 0.49, green: 0.82, blue: 0.60, alpha: 1.0)
    )
    drawParagraph(
        "Live PR alert",
        in: NSRect(x: 120, y: 730, width: 640, height: 180),
        font: .systemFont(ofSize: 54, weight: .bold),
        color: .white,
        lineHeight: 1.02
    )
    drawParagraph(
        "When a segment best is beaten, kRecords plays a success chime and shows the recorded time plus time saved.",
        in: NSRect(x: 120, y: 550, width: 620, height: 130),
        font: .systemFont(ofSize: 22, weight: .regular),
        color: NSColor(calibratedWhite: 0.86, alpha: 1.0),
        lineHeight: 1.2
    )

    let featureBox = NSRect(x: 120, y: 320, width: 500, height: 140)
    let featurePath = roundedRectPath(featureBox, radius: 28)
    NSColor(calibratedRed: 1.0, green: 1.0, blue: 1.0, alpha: 0.08).setFill()
    featurePath.fill()
    NSColor(calibratedRed: 0.49, green: 0.82, blue: 0.60, alpha: 0.35).setStroke()
    featurePath.lineWidth = 2
    featurePath.stroke()
    drawParagraph(
        "Segment name + recorded time + time saved",
        in: NSRect(x: featureBox.minX + 28, y: featureBox.minY + 52, width: featureBox.width - 56, height: featureBox.height - 70),
        font: .systemFont(ofSize: 21, weight: .medium),
        color: .white,
        lineHeight: 1.22
    )

    drawParagraph(
        "kRecords",
        in: NSRect(x: 120, y: 160, width: 260, height: 48),
        font: .systemFont(ofSize: 34, weight: .bold),
        color: NSColor(calibratedRed: 0.49, green: 0.82, blue: 0.60, alpha: 1.0)
    )
    drawParagraph(
        "Karoo app for local segment PR alerts",
        in: NSRect(x: 120, y: 126, width: 440, height: 36),
        font: .systemFont(ofSize: 20, weight: .medium),
        color: NSColor(calibratedWhite: 0.78, alpha: 1.0)
    )

    let deviceRect = NSRect(x: 920, y: 120, width: 420, height: 920)
    let deviceShadow = roundedRectPath(deviceRect.offsetBy(dx: 0, dy: -18), radius: 42)
    NSColor(calibratedWhite: 0.0, alpha: 0.22).setFill()
    deviceShadow.fill()

    let devicePath = roundedRectPath(deviceRect, radius: 42)
    NSColor(calibratedWhite: 0.98, alpha: 1.0).setFill()
    devicePath.fill()
    NSColor(calibratedRed: 0.76, green: 0.80, blue: 0.75, alpha: 1.0).setStroke()
    devicePath.lineWidth = 3
    devicePath.stroke()

    let screenRect = deviceRect.insetBy(dx: 18, dy: 18)
    let screenPath = roundedRectPath(screenRect, radius: 28)
    NSGraphicsContext.current?.saveGraphicsState()
    screenPath.addClip()

    let rideGradient = NSGradient(colors: [
        NSColor(calibratedRed: 0.06, green: 0.10, blue: 0.12, alpha: 1.0),
        NSColor(calibratedRed: 0.12, green: 0.16, blue: 0.18, alpha: 1.0),
    ])!
    rideGradient.draw(in: screenRect, angle: 90)

    NSColor(calibratedWhite: 1.0, alpha: 0.05).setStroke()
    for i in 0..<8 {
        let path = NSBezierPath()
        let y = screenRect.minY + CGFloat(i) * 110 + 30
        path.move(to: NSPoint(x: screenRect.minX - 20, y: y))
        path.curve(
            to: NSPoint(x: screenRect.maxX + 20, y: y + 18),
            controlPoint1: NSPoint(x: screenRect.minX + 120, y: y + 46),
            controlPoint2: NSPoint(x: screenRect.minX + 240, y: y - 34)
        )
        path.lineWidth = 2
        path.stroke()
    }

    let metricFont = NSFont.monospacedDigitSystemFont(ofSize: 24, weight: .semibold)
    drawCenteredParagraph("31.8 km/h", in: NSRect(x: screenRect.minX + 18, y: screenRect.maxY - 88, width: 126, height: 34), font: metricFont, color: NSColor(calibratedWhite: 0.85, alpha: 1.0))
    drawCenteredParagraph("182 bpm", in: NSRect(x: screenRect.midX - 63, y: screenRect.maxY - 88, width: 126, height: 34), font: metricFont, color: NSColor(calibratedWhite: 0.85, alpha: 1.0))
    drawCenteredParagraph("6.4 km", in: NSRect(x: screenRect.maxX - 144, y: screenRect.maxY - 88, width: 126, height: 34), font: metricFont, color: NSColor(calibratedWhite: 0.85, alpha: 1.0))

    let alertRect = NSRect(x: screenRect.minX + 34, y: screenRect.midY - 70, width: screenRect.width - 68, height: 160)
    let alertShadow = roundedRectPath(alertRect.offsetBy(dx: 0, dy: -12), radius: 28)
    NSColor(calibratedWhite: 0.0, alpha: 0.24).setFill()
    alertShadow.fill()

    let alertPath = roundedRectPath(alertRect, radius: 28)
    NSColor(calibratedRed: 0.13, green: 0.49, blue: 0.23, alpha: 1.0).setFill()
    alertPath.fill()

    let badgeRect = NSRect(x: alertRect.minX + 24, y: alertRect.maxY - 54, width: 92, height: 32)
    let badgePath = roundedRectPath(badgeRect, radius: 14)
    NSColor(calibratedWhite: 1.0, alpha: 0.16).setFill()
    badgePath.fill()
    drawCenteredParagraph("kRecords", in: NSRect(x: badgeRect.minX, y: badgeRect.minY + 5, width: badgeRect.width, height: 22), font: .systemFont(ofSize: 14, weight: .bold), color: .white)

    drawParagraph(
        "New PR",
        in: NSRect(x: alertRect.minX + 24, y: alertRect.midY + 16, width: alertRect.width - 48, height: 42),
        font: .systemFont(ofSize: 30, weight: .bold),
        color: .white
    )
    drawParagraph(
        "Ocean Cliff Rd",
        in: NSRect(x: alertRect.minX + 24, y: alertRect.minY + 44, width: alertRect.width - 48, height: 30),
        font: .systemFont(ofSize: 20, weight: .semibold),
        color: .white,
        lineHeight: 1.1
    )
    drawParagraph(
        "2:34, saved 0:08",
        in: NSRect(x: alertRect.minX + 24, y: alertRect.minY + 18, width: alertRect.width - 48, height: 28),
        font: .systemFont(ofSize: 20, weight: .medium),
        color: .white,
        lineHeight: 1.1
    )

    NSGraphicsContext.current?.restoreGraphicsState()
    NSGraphicsContext.restoreGraphicsState()

    guard let data = rep.representation(using: .png, properties: [:]) else {
        throw NSError(domain: "krecords", code: 2)
    }
    try data.write(to: URL(fileURLWithPath: outputPath))
    print(outputPath)
} catch {
    fputs("Failed to render PR alert image: \(error)\n", stderr)
}
