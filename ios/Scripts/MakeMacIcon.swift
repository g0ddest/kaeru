#!/usr/bin/env swift
// Renders the Mac app icon from the iOS one, into the same AppIcon set.
//
// iOS masks a full-bleed square itself; macOS draws the icon as it is given, so the iOS artwork there
// would be a bare square beside every rounded one in the Dock. This puts it on macOS's grid instead:
// a 1024 canvas, the tile 824 points wide in the middle with Apple's continuous corners, a soft shadow
// under it, the rest transparent. Every size is drawn from scratch rather than scaled down from 1024,
// so the edge stays crisp at 16 points too. Contents.json lists the files by these names.
//
// Run after the iOS icon changes:  swift ios/Scripts/MakeMacIcon.swift

import CoreGraphics
import Foundation
import ImageIO
import SwiftUI
import UniformTypeIdentifiers

let iconSet = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent()
    .appendingPathComponent("../App/Assets.xcassets/AppIcon.appiconset")
    .standardizedFileURL
let source = iconSet.appendingPathComponent("AppIcon.png")

// The grid of Apple's macOS icon template, in pixels of the 1024 canvas.
let canvas: CGFloat = 1024
let tile = CGRect(x: 100, y: 100, width: 824, height: 824)
let cornerRadius: CGFloat = 185.4
let shadowOffset = CGSize(width: 0, height: -10)
let shadowBlur: CGFloat = 20
let shadowOpacity: CGFloat = 0.3

guard let input = CGImageSourceCreateWithURL(source as CFURL, nil),
      let artwork = CGImageSourceCreateImageAtIndex(input, 0, nil) else {
    fatalError("Не удалось прочитать \(source.path)")
}

func render(pixels: Int) -> CGImage {
    let space = CGColorSpace(name: CGColorSpace.sRGB)!
    guard let context = CGContext(data: nil, width: pixels, height: pixels, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
        fatalError("Не удалось создать холст \(pixels)×\(pixels)")
    }
    let scale = CGFloat(pixels) / canvas
    context.scaleBy(x: scale, y: scale)
    context.interpolationQuality = .high
    let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous).path(in: tile).cgPath
    // The shadow is cast by an opaque tile of the artwork's own background, then covered by it.
    context.saveGState()
    context.setShadow(offset: CGSize(width: shadowOffset.width * scale, height: shadowOffset.height * scale),
                      blur: shadowBlur * scale, color: CGColor(gray: 0, alpha: shadowOpacity))
    context.addPath(shape)
    context.setFillColor(CGColor(srgbRed: 0x0B / 255, green: 0x0C / 255, blue: 0x10 / 255, alpha: 1))
    context.fillPath()
    context.restoreGState()
    context.addPath(shape)
    context.clip()
    context.draw(artwork, in: tile)
    return context.makeImage()!
}

// Point size and scale, as the asset catalog names them.
let sizes: [(points: Int, scale: Int)] = [(16, 1), (16, 2), (32, 1), (32, 2), (128, 1), (128, 2), (256, 1), (256, 2), (512, 1), (512, 2)]
for size in sizes {
    let name = "AppIcon-mac-\(size.points)" + (size.scale == 1 ? "" : "@\(size.scale)x") + ".png"
    let url = iconSet.appendingPathComponent(name)
    guard let output = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
        fatalError("Не удалось записать \(url.path)")
    }
    CGImageDestinationAddImage(output, render(pixels: size.points * size.scale), nil)
    guard CGImageDestinationFinalize(output) else { fatalError("Не удалось записать \(url.path)") }
    print("\(name): \(size.points * size.scale) px")
}
