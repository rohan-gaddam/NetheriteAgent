import { useEffect, useRef, useState, type ReactNode } from 'react';
import { cn } from '../../lib/utils';

/**
 * Scroll-triggered entrance for existing content sections. The content renders
 * visibly by default; when IntersectionObserver is available (and motion is
 * allowed) it starts settled-hidden and reveals once on entering the viewport.
 * Never hides content from assistive tech, never delays data, never moves layout.
 */
export function Reveal({
  children,
  className,
  delayMs = 0,
}: {
  children: ReactNode;
  className?: string;
  delayMs?: number;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(false);
  // Arm synchronously during first render (never in an effect) so there is no
  // visible-then-hidden flicker before paint. IO support and reduced-motion are
  // evaluated here; no observation work happens during render.
  const [armed] = useState(() => {
    if (typeof IntersectionObserver === 'undefined') return false;
    if (typeof window !== 'undefined'
      && typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      return false;
    }
    return true;
  });

  useEffect(() => {
    if (!armed) return;
    const el = ref.current;
    if (!el) return;
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            setVisible(true);
            observer.disconnect();
          }
        }
      },
      { rootMargin: '0px 0px -8% 0px', threshold: 0.08 },
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  return (
    <div
      ref={ref}
      className={cn('reveal', armed && !visible && 'reveal-hidden', className)}
      style={visible && delayMs > 0 ? { transitionDelay: `${delayMs}ms` } : undefined}
    >
      {children}
    </div>
  );
}
