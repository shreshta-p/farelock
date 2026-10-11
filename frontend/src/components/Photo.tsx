import { creditFor, src, srcSet } from '../lib/photos'

type Props = {
  photo: string
  alt?: string
  /** Tailwind classes for the frame (set the aspect ratio here). */
  className?: string
  /** Classes for the outer <figure>, e.g. grid placement. */
  figureClassName?: string
  sizes?: string
  priority?: boolean
  caption?: boolean
}

/** A responsive photo in a fixed-ratio frame, optionally with an editorial credit caption. */
export function Photo({ photo, alt, className = 'aspect-[4/3]', figureClassName = '', sizes = '(min-width: 1024px) 50vw, 100vw', priority, caption }: Props) {
  const credit = creditFor(photo)
  return (
    <figure className={`m-0 ${figureClassName}`}>
      <div className={`photo-zoom relative overflow-hidden bg-paper-deep ${className}`}>
        <img
          src={src(photo)}
          srcSet={srcSet(photo)}
          sizes={sizes}
          alt={alt ?? credit?.alt ?? ''}
          loading={priority ? 'eager' : 'lazy'}
          fetchPriority={priority ? 'high' : 'auto'}
          decoding="async"
          className="absolute inset-0 h-full w-full object-cover"
        />
      </div>
      {caption && credit && (
        <figcaption className="mt-2 text-[0.7rem] leading-snug text-muted">
          Photo:{' '}
          <a href={credit.url} target="_blank" rel="noreferrer" className="link">
            {credit.author}
          </a>{' '}
          · {credit.source} · {credit.license}
        </figcaption>
      )}
    </figure>
  )
}
