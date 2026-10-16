import { useEffect, useRef } from 'react';
import { X } from 'lucide-react';

export function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  variant = 'danger',
  onConfirm,
  onCancel,
}: {
  open: boolean;
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  variant?: 'danger' | 'warning' | 'info';
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const dialogRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (open) {
      dialogRef.current?.focus();
    }
  }, [open]);

  useEffect(() => {
    const handleEsc = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel();
    };
    if (open) {
      document.addEventListener('keydown', handleEsc);
      return () => document.removeEventListener('keydown', handleEsc);
    }
  }, [open, onCancel]);

  if (!open) return null;

  const btnClasses = {
    danger: 'bg-red-600 hover:bg-red-500 text-white',
    warning: 'bg-amber-600 hover:bg-amber-500 text-white',
    info: 'btn-luminous text-white',
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={onCancel} />
      <div
        ref={dialogRef}
        tabIndex={-1}
        className="relative panel-featured rounded-xl w-full max-w-md mx-4 p-6 focus:outline-none"
      >
        <div className="flex items-start justify-between mb-4">
          <h3 className="text-base font-bold text-white">{title}</h3>
          <button onClick={onCancel} className="text-slate-400 hover:text-white transition-colors">
            <X className="h-4 w-4" />
          </button>
        </div>
        <p className="text-sm text-slate-300 mb-6 leading-relaxed">{message}</p>
        <div className="flex justify-end space-x-3">
          <button
            onClick={onCancel}
            className="px-4 py-2 text-sm font-medium text-slate-300 bg-slate-800 border border-slate-700 rounded-lg hover:bg-slate-700 transition-colors"
          >
            {cancelLabel}
          </button>
          <button
            onClick={onConfirm}
            className={`px-4 py-2 text-sm font-medium rounded-lg transition-colors ${btnClasses[variant]}`}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
