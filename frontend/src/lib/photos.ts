import data from '../data/photo-credits.json'

export type Credit = { author: string; source: string; url: string; license: string; licenseUrl?: string | null; alt: string }

const credits = data.credits as Record<string, Credit>

export const heroPhoto = data.hero
export const destinationPhotos = data.destinations as Record<string, string>

export function creditFor(key: string): Credit | undefined {
  return credits[key]
}

/** Each photo exists at 800 and 1600 pixels wide; the browser picks one. */
export function srcSet(key: string) {
  return `/images/${key}-800.webp 800w, /images/${key}-1600.webp 1600w`
}

export function src(key: string) {
  return `/images/${key}-800.webp`
}
