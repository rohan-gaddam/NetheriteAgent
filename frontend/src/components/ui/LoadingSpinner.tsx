import { Loader2 } from 'lucide-react';
import { cn } from '../../lib/utils';

export function LoadingSpinner({ className, size = 'md' }: { className?: string; size?: 'sm' | 'md' | 'lg' }) {
  const sizeClasses = {
    sm: 'h-4 w-4',
    md: 'h-6 w-6',
    lg: 'h-10 w-10',
  };
  return <Loader2 className={cn('animate-spin text-cyan-300', sizeClasses[size], className)} />;
}

export function LoadingPage() {
  return (
    <div className="flex items-center justify-center py-24">
      <LoadingSpinner size="lg" />
    </div>
  );
}

export function LoadingCard() {
  return (
    <div className="glass-panel rounded-xl p-6 animate-pulse">
      <div className="h-4 bg-slate-800 rounded w-1/3 mb-3" />
      <div className="h-3 bg-slate-800 rounded w-2/3 mb-2" />
      <div className="h-3 bg-slate-800 rounded w-1/2" />
    </div>
  );
}

export function LoadingSkeleton({ lines = 3 }: { lines?: number }) {
  return (
    <div className="space-y-3 animate-pulse">
      {Array.from({ length: lines }).map((_, i) => (
        <div key={i} className="h-3 bg-slate-800 rounded" style={{ width: `${70 + Math.random() * 30}%` }} />
      ))}
    </div>
  );
}
