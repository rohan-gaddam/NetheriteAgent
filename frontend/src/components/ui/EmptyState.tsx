import React from 'react';
import { AlertCircle, RefreshCw } from 'lucide-react';
import { LoadingSpinner } from './LoadingSpinner';

export function EmptyState({
  icon,
  title,
  description,
  action,
}: {
  icon?: React.ReactNode;
  title: string;
  description?: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      {icon && <div className="text-slate-600 mb-4">{icon}</div>}
      <h3 className="text-sm font-semibold text-slate-300">{title}</h3>
      {description && <p className="mt-1.5 text-xs text-slate-500 max-w-sm">{description}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function ErrorState({
  title = 'Something went wrong',
  message,
  onRetry,
}: {
  title?: string;
  message?: string;
  onRetry?: () => void;
}) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <AlertCircle className="h-8 w-8 text-red-400 mb-3" />
      <h3 className="text-sm font-semibold text-slate-300">{title}</h3>
      {message && <p className="mt-1.5 text-xs text-slate-500 max-w-sm">{message}</p>}
      {onRetry && (
        <button
          onClick={onRetry}
          className="mt-4 inline-flex items-center space-x-2 px-3 py-1.5 text-xs font-medium text-slate-300 bg-slate-800 border border-slate-700 rounded-lg hover:bg-slate-700 transition-colors"
        >
          <RefreshCw className="h-3.5 w-3.5" />
          <span>Retry</span>
        </button>
      )}
    </div>
  );
}

export function LoadingCard() {
  return (
      <div className="glass-panel rounded-xl p-6">
      <div className="flex items-center justify-center py-8">
        <LoadingSpinner size="md" />
      </div>
    </div>
  );
}
