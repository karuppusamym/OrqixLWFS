/** File types the platform accepts (docs/build/S25-attachments-stages.md), with the words people use for them. */
export const FILE_TYPES: Record<string, { label: string; extensions: string[] }> = {
  pdf: { label: 'PDF', extensions: ['.pdf'] },
  png: { label: 'PNG image', extensions: ['.png'] },
  jpeg: { label: 'JPEG image', extensions: ['.jpg', '.jpeg'] },
  txt: { label: 'text file', extensions: ['.txt'] },
  csv: { label: 'CSV file', extensions: ['.csv'] },
  docx: { label: 'Word document', extensions: ['.docx'] },
  xlsx: { label: 'Excel workbook', extensions: ['.xlsx'] },
  pptx: { label: 'PowerPoint presentation', extensions: ['.pptx'] },
}

export const ALL_FILE_TYPES = Object.keys(FILE_TYPES)
export const MAX_FILE_BYTES = 10 * 1024 * 1024

export function formatBytes(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(bytes % (1024 * 1024) === 0 ? 0 : 1)} MB`
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`
  return `${bytes} bytes`
}

/** "PDF, PNG image or JPEG image" for a field's accepted types. */
export function describeTypes(types: readonly string[] | undefined): string {
  const labels = (types && types.length > 0 ? types : ALL_FILE_TYPES).map((t) => FILE_TYPES[t]?.label ?? t)
  return labels.length <= 1 ? (labels[0] ?? '') : `${labels.slice(0, -1).join(', ')} or ${labels.at(-1)}`
}

/** The `accept` attribute of a file input for the field's types. */
export function acceptAttribute(types: readonly string[] | undefined): string {
  return (types && types.length > 0 ? types : ALL_FILE_TYPES).flatMap((t) => FILE_TYPES[t]?.extensions ?? []).join(',')
}

/** Checks that need no server: a quick answer before anything is sent. The server still decides. */
export function checkFile(file: File, types: readonly string[] | undefined, maxBytes: number): string | null {
  if (file.size === 0) return 'This file is empty.'
  if (file.size > maxBytes) return `This file is ${formatBytes(file.size)}. The largest allowed is ${formatBytes(maxBytes)}.`
  const name = file.name.toLowerCase()
  const allowed = (types && types.length > 0 ? types : ALL_FILE_TYPES).flatMap((t) => FILE_TYPES[t]?.extensions ?? [])
  if (!allowed.some((ext) => name.endsWith(ext))) return `Choose a ${describeTypes(types)}.`
  return null
}

