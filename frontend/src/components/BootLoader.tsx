import { Shield } from 'lucide-react';

/**
 * First-entry loading screen, shown only while initial authentication state
 * is unresolved. Brand mark inside a luminous ring with an indeterminate
 * progress line — no fabricated percentages; it reflects the real auth
 * readiness signal and never delays interaction once resolved.
 */
export function BootLoader() {
  return (
    <div
      className="boot-loader"
      role="status"
      aria-label="Loading NetheriteAgent"
    >
      <div className="boot-loader-core">
        <div className="boot-ring" aria-hidden="true">
          <div className="boot-ring-spin" />
          <div className="boot-mark">
            <Shield className="boot-mark-icon" />
          </div>
        </div>
        <div className="boot-wordmark" aria-hidden="true">
          Netherite<span>Agent</span>
        </div>
        <div className="boot-progress" aria-hidden="true">
          <div className="boot-progress-sweep" />
        </div>
      </div>
    </div>
  );
}
