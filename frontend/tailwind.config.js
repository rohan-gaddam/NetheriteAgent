/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      fontFamily: {
        sans: ['InterVariable', 'Inter', '-apple-system', 'BlinkMacSystemFont', "'Segoe UI'", 'Roboto', 'sans-serif'],
        display: ['Quicksand', 'InterVariable', 'Inter', 'sans-serif'],
      },
      colors: {
        brand: {
          50: '#f0f9ff',
          100: '#e0f2fe',
          500: '#0ea5e9',
          600: '#0284c7',
          700: '#0369a1',
          900: '#0c4a6e',
        },
        netherite: {
          dark: '#0f172a',
          card: '#1e293b',
          border: '#334155',
          accent: '#6366f1'
        },
        neon: {
          cyan: '#22d3ee',
          ice: '#67e8f9',
          blue: '#3b82f6',
          royal: '#6366f1',
          violet: '#a78bfa',
        }
      },
      boxShadow: {
        glow: '0 0 20px -4px rgba(34, 211, 238, 0.55), 0 0 48px -12px rgba(59, 130, 246, 0.4)',
        'glow-lg': '0 0 34px -4px rgba(34, 211, 238, 0.65), 0 0 80px -12px rgba(59, 130, 246, 0.5)',
        'glow-violet': '0 0 20px -4px rgba(167, 139, 250, 0.55)',
        glass: '0 22px 60px -20px rgba(0, 0, 0, 0.8), 0 0 32px -18px rgba(34, 211, 238, 0.22), inset 0 1px 0 0 rgba(186, 232, 253, 0.16)',
        'glass-sm': '0 12px 34px -18px rgba(0, 0, 0, 0.75), 0 0 22px -14px rgba(34, 211, 238, 0.18), inset 0 1px 0 0 rgba(186, 232, 253, 0.12)',
      },
    },
  },
  plugins: [],
}
