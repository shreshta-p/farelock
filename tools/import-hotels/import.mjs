// One-time import: real hotel facts from Wikidata + photos from Wikimedia Commons and Unsplash.
// Produces committed seed files, so the app itself never calls these services.
//
//   cd tools/import-hotels && pnpm install && pnpm import
//
// Outputs:
//   reservation-service/.../V4__seed_catalog.sql   hotels, room types (+ the hidden load-test hotel)
//   pricing-service/.../V2__seed_rates.sql         base nightly rates
//   frontend/public/images/**.webp                 resized photos (800w and 1600w)
//   frontend/src/data/photo-credits.json           per-photo attribution shown in the UI
//   CREDITS.md                                     full attribution and licences
//   tools/import-hotels/wikidata-snapshot.json     the exact facts used, for review
import { readFile, writeFile, mkdir, access } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import sharp from 'sharp'

const here = dirname(fileURLToPath(import.meta.url))
const root = join(here, '..', '..')
const USER_AGENT = 'FareLockImport/1.0 (portfolio demo; https://github.com/shreshta-p/farelock)'
const WIDTHS = [800, 1600]

const config = JSON.parse(await readFile(join(here, 'hotels.json'), 'utf8'))
const unsplash = Object.fromEntries(
  JSON.parse(await readFile(join(here, 'unsplash-photos.json'), 'utf8')).map((p) => [p.id, p]),
)

async function getJson(url) {
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } })
  if (!res.ok) throw new Error(`${res.status} for ${url}`)
  return res.json()
}

const stripHtml = (s = '') => s.replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim()
const sql = (s) => `'${String(s).replace(/'/g, "''")}'`
const sqlArray = (items) => `ARRAY[${items.map(sql).join(', ')}]::text[]`

// --- 1. Wikidata facts -------------------------------------------------------
const qids = config.hotels.map((h) => h.qid).join('|')
const entities = (
  await getJson(`https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${qids}&props=claims|labels&languages=en&format=json`)
).entities

const claim = (entity, property) => entity.claims[property]?.[0]?.mainsnak?.datavalue?.value

const facts = config.hotels.map((h) => {
  const e = entities[h.qid]
  const coord = claim(e, 'P625')
  const image = claim(e, 'P18')
  if (!coord || !image) throw new Error(`${h.qid} is missing coordinates or an image`)
  return {
    qid: h.qid,
    wikidataLabel: e.labels.en?.value,
    latitude: Number(coord.latitude.toFixed(6)),
    longitude: Number(coord.longitude.toFixed(6)),
    inception: claim(e, 'P571')?.time?.slice(1, 5) ?? null,
    commonsFile: image,
  }
})

// --- 2. Commons licence metadata for each hotel's exterior photo -----------------
const titles = facts.map((f) => `File:${f.commonsFile}`).join('|')
const pages = (
  await getJson(
    `https://commons.wikimedia.org/w/api.php?action=query&titles=${encodeURIComponent(titles)}` +
      `&prop=imageinfo&iiprop=url|extmetadata&iiurlwidth=1600&format=json`,
  )
).query.pages
const commons = {}
for (const page of Object.values(pages)) {
  const info = page.imageinfo[0]
  const meta = info.extmetadata
  commons[page.title.replace(/^File:/, '')] = {
    url: info.thumburl,
    page: info.descriptionurl,
    author: stripHtml(meta.Artist?.value) || 'Unknown',
    license: meta.LicenseShortName?.value,
    licenseUrl: meta.LicenseUrl?.value ?? null,
  }
}

// --- 3. Download + resize photos -----------------------------------------------
const credits = {}

async function exists(path) {
  try { await access(path); return true } catch { return false }
}

async function savePhoto(key, sourceUrl) {
  const base = join(root, 'frontend', 'public', 'images', key)
  if (await exists(`${base}-1600.webp`)) return
  await mkdir(dirname(base), { recursive: true })
  const res = await fetch(sourceUrl, { headers: { 'User-Agent': USER_AGENT } })
  if (!res.ok) throw new Error(`${res.status} downloading ${sourceUrl}`)
  const input = Buffer.from(await res.arrayBuffer())
  for (const width of WIDTHS) {
    await sharp(input).resize({ width, withoutEnlargement: true }).webp({ quality: 78 }).toFile(`${base}-${width}.webp`)
  }
  console.log('saved', key)
}

async function saveUnsplash(id) {
  const photo = unsplash[id]
  if (!photo) throw new Error(`Unknown Unsplash photo ${id}`)
  const key = `photos/${id}`
  await savePhoto(key, `${photo.raw}?w=1600&q=85&fm=jpg`)
  credits[key] = {
    author: photo.photographer,
    source: 'Unsplash',
    url: `https://unsplash.com/photos/${id}`,
    license: 'Unsplash License',
    alt: photo.alt,
  }
  return key
}

const photoRef = async (ref) => saveUnsplash(ref.split(':')[1])

for (const [i, h] of config.hotels.entries()) {
  const c = commons[facts[i].commonsFile]
  const key = `hotels/${h.slug}-exterior`
  await savePhoto(key, c.url)
  credits[key] = { author: c.author, source: 'Wikimedia Commons', url: c.page, license: c.license, licenseUrl: c.licenseUrl, alt: `${h.name}, exterior` }
}
const heroKey = await saveUnsplash(config.heroPhoto)
const destinationKeys = {}
for (const [city, id] of Object.entries(config.destinationPhotos)) destinationKeys[city] = await saveUnsplash(id)

// --- 4. Seed SQL -------------------------------------------------------------------
const header = '-- GENERATED by tools/import-hotels/import.mjs. Do not edit by hand; edit hotels.json and re-run.\n'
const catalog = [header, '-- Real hotel facts from Wikidata (CC0). Rooms, capacities and descriptions are FareLock demo data.\n']
const rates = [header, '-- Base nightly rates (demo data). Room type ids match the reservation service catalog.\n']

for (const [i, h] of config.hotels.entries()) {
  const f = facts[i]
  const gallery = [`hotels/${h.slug}-exterior`]
  for (const ref of h.gallery) gallery.push(await photoRef(ref))
  catalog.push(
    `INSERT INTO hotels (id, name, city, country, address, latitude, longitude, star_rating, description, amenities, images, source_url) VALUES (` +
      `${h.id}, ${sql(h.name)}, ${sql(h.city)}, ${sql(h.country)}, ${sql(h.address)}, ${f.latitude}, ${f.longitude}, ${h.stars}, ` +
      `${sql(h.description)}, ${sqlArray(h.amenities)}, ${sqlArray(gallery)}, ${sql(`https://www.wikidata.org/wiki/${h.qid}`)});`,
  )
  for (const [j, [name, description, bed, size, guests, price, roomsPerNight, image]] of h.rooms.entries()) {
    const roomId = h.id * 100 + j + 1
    catalog.push(
      `INSERT INTO room_types (id, hotel_id, name, description, bed, size_sqm, max_guests, image, rooms_per_night) VALUES (` +
        `${roomId}, ${h.id}, ${sql(name)}, ${sql(description)}, ${sql(bed)}, ${size}, ${guests}, ${sql(await photoRef(image))}, ${roomsPerNight});`,
    )
    rates.push(`INSERT INTO room_rates (room_type_id, hotel_id, base_price, currency) VALUES (${roomId}, ${h.id}, ${price}, ${sql(h.currency)});`)
  }
}

const lt = config.loadTestHotel
catalog.push(
  '\n-- Hidden hotel used only by k6 load tests (listed = false keeps it out of search).',
  `INSERT INTO hotels (id, name, city, country, address, description, listed) VALUES (${lt.id}, ${sql(lt.name)}, ${sql(lt.city)}, ${sql(lt.country)}, 'n/a', 'Synthetic inventory for load testing.', false);`,
)
rates.push('\n-- Load-test hotel')
for (const [id, name, roomsPerNight, price] of lt.rooms) {
  catalog.push(
    `INSERT INTO room_types (id, hotel_id, name, description, bed, size_sqm, max_guests, rooms_per_night) VALUES (${id}, ${lt.id}, ${sql(name)}, 'Load test', 'n/a', 1, 1, ${roomsPerNight});`,
  )
  rates.push(`INSERT INTO room_rates (room_type_id, hotel_id, base_price, currency) VALUES (${id}, ${lt.id}, ${price}, ${sql(lt.currency)});`)
}

await writeFile(join(root, 'reservation-service/src/main/resources/db/migration/V4__seed_catalog.sql'), catalog.join('\n') + '\n')
await writeFile(join(root, 'pricing-service/src/main/resources/db/migration/V2__seed_rates.sql'), rates.join('\n') + '\n')

// --- 5. Credits ------------------------------------------------------------------
const frontendData = { hero: heroKey, destinations: destinationKeys, credits }
await mkdir(join(root, 'frontend/src/data'), { recursive: true })
await writeFile(join(root, 'frontend/src/data/photo-credits.json'), JSON.stringify(frontendData, null, 2) + '\n')
await writeFile(join(here, 'wikidata-snapshot.json'), JSON.stringify(facts, null, 2) + '\n')

const lines = [
  '# Credits',
  '',
  'GENERATED by `tools/import-hotels/import.mjs`.',
  '',
  '## Hotel facts',
  '',
  'Hotel names, coordinates and the hotels themselves come from [Wikidata](https://www.wikidata.org) (CC0).',
  'FareLock is not affiliated with any of these hotels. Room types, prices, availability and descriptions are demo data.',
  '',
  '## Photos',
  '',
  'Hotel exteriors are real photos of each building from Wikimedia Commons, used under the licence shown.',
  'Interiors and destination photos come from Unsplash under the [Unsplash License](https://unsplash.com/license). They are',
  "representative only and do not show the specific hotel's rooms. Images were resized and converted to WebP.",
  '',
  '| Image | Author | Source | Licence |',
  '|---|---|---|---|',
  ...Object.entries(credits)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([key, c]) => `| \`${key}\` | ${c.author.replace(/\|/g, '/')} | [${c.source}](${c.url}) | ${c.licenseUrl ? `[${c.license}](${c.licenseUrl})` : c.license} |`),
  '',
]
await writeFile(join(root, 'CREDITS.md'), lines.join('\n'))
console.log(`Done: ${config.hotels.length} hotels, ${Object.keys(credits).length} photos.`)
