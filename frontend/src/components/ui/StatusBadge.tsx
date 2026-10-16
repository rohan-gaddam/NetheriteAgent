import { cn } from '../../lib/utils';

type BadgeVariant = 'success' | 'danger' | 'warning' | 'info' | 'neutral' | 'purple';

const variantClasses: Record<BadgeVariant, string> = {
  success: 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20',
  danger: 'bg-red-500/10 text-red-400 border-red-500/20',
  warning: 'bg-amber-500/10 text-amber-400 border-amber-500/20',
  info: 'bg-sky-500/10 text-sky-400 border-sky-500/20',
  neutral: 'bg-slate-500/10 text-slate-400 border-slate-500/20',
  purple: 'bg-purple-500/10 text-purple-400 border-purple-500/20',
};

export function StatusBadge({
  label,
  variant = 'neutral',
  dot = true,
  className,
}: {
  label: string;
  variant?: BadgeVariant;
  dot?: boolean;
  className?: string;
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center space-x-1.5 px-2.5 py-0.5 rounded-full text-xs font-semibold border',
        variantClasses[variant],
        className,
      )}
    >
      {dot && (
        <span
          className={cn(
            'h-1.5 w-1.5 rounded-full',
            variant === 'success' && 'bg-emerald-400',
            variant === 'danger' && 'bg-red-400',
            variant === 'warning' && 'bg-amber-400',
            variant === 'info' && 'bg-sky-400',
            variant === 'neutral' && 'bg-slate-400',
            variant === 'purple' && 'bg-purple-400',
          )}
        />
      )}
      <span>{label}</span>
    </span>
  );
}

export function getDecisionBadgeVariant(decision: string): BadgeVariant {
  switch (decision) {
    case 'ALLOW': return 'success';
    case 'DENY': return 'danger';
    case 'HUMAN_APPROVAL': return 'warning';
    default: return 'neutral';
  }
}

export function getApprovalBadgeVariant(status: string): BadgeVariant {
  switch (status) {
    case 'PENDING': return 'warning';
    case 'APPROVED': return 'success';
    case 'REJECTED': return 'danger';
    case 'EXPIRED': return 'neutral';
    case 'CANCELLED': return 'neutral';
    default: return 'neutral';
  }
}
