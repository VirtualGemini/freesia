import type { CSSProperties } from 'vue'

/** Size the original MessageBox from its copy and the longest value in each table column. */
export function getConfirmationTableLayout(message: string, columns: string[][], hasIcon = false) {
  const rootStyle = getComputedStyle(document.documentElement)
  const fontSize = Number.parseFloat(rootStyle.getPropertyValue('--el-font-size-base')) || 14
  const context = document.createElement('canvas').getContext('2d')
  if (context) context.font = `${fontSize}px ${getComputedStyle(document.body).fontFamily}`
  const measure = (text: string) => context?.measureText(text).width ?? text.length * fontSize
  const columnWidths = columns.map((values) =>
    Math.ceil(values.reduce((longest, value) => Math.max(longest, measure(value)), 0) + 32)
  )
  const totalColumnWidth = columnWidths.reduce((sum, width) => sum + width, 0)
  // Use small relative weights so Element Plus can fit every column even on narrow screens.
  const columnMinWidths = columnWidths.map((width) =>
    Math.max(48, Math.floor((width / totalColumnWidth) * 120))
  )
  const totalMinWidth = columnMinWidths.reduce((sum, width) => sum + width, 0)
  const unwrappedTableWidth = columnWidths.reduce(
    (widest, width, index) =>
      Math.max(widest, Math.ceil((width * totalMinWidth) / columnMinWidths[index])),
    0
  )
  // Aim for two copy lines; wrap table content once the dialog reaches its width limit.
  const preferredWidth = Math.min(
    1280,
    Math.max(
      420,
      Math.ceil(measure(message) / 2) + 48 + (hasIcon ? 36 : 0),
      unwrappedTableWidth + 48
    )
  )
  const style: CSSProperties = {
    // The native overlay has its own padding; viewport-based widths can overflow its content box.
    width: `min(${preferredWidth}px, 100%)`,
    maxWidth: '100%'
  }
  return {
    columnMinWidths,
    // Keep a usable body below the header; the native MessageBox can scroll on very short screens.
    tableMaxHeight: 'clamp(160px, calc(100dvh - 280px), 600px)',
    style
  }
}
