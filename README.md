# PSM Android OCR

## Mục tiêu
Android OCR automation cho PSM.

## Kiến trúc
- Overlay bubble service
- ML Kit OCR
- Relative coordinate crop
- Debug crop export
- OCR observability

## Workflow
capture → crop → OCR → debug → tune

## Known Issues
- multi-layout alignment
- thumbnail anchoring
- dynamic height rows

## Rules
- không hardcode pixel
- luôn export debug crop
- inspect OCR raw text trước
- preserve overlay logs

# PSM Android OCR

Android OCR automation system for PSM ecosystem.

## Features
- Overlay bubble service
- ML Kit OCR
- Relative coordinate crop
- Google Sheets sync
- OCR debug export

## Workflow
capture → crop → OCR → inspect → tune

## Current Issues
- multi-layout handling
- anchor drift
- dynamic row alignment