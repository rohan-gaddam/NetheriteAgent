import React from 'react';
import { Shield, Bot, Database, ScrollText, CheckSquare, Activity } from 'lucide-react';

export const Navbar: React.FC = () => {
  return (
    <header className="border-b border-cyan-400/15 bg-[#070b1c]/80 backdrop-blur-xl sticky top-0 z-50">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
        <div className="flex items-center space-x-3">
          <div className="h-9 w-9 rounded-lg bg-gradient-to-br from-cyan-400 via-blue-500 to-violet-500 flex items-center justify-center shadow-glow">
            <Shield className="h-5 w-5 text-white" />
          </div>
          <div>
            <span className="font-display font-bold text-lg text-white tracking-tight">NetheriteAgent</span>
            <span className="ml-2 text-xs bg-cyan-400/10 text-cyan-300 px-2 py-0.5 rounded border border-cyan-400/25 font-medium">Control Plane</span>
          </div>
        </div>

        <nav className="flex space-x-1">
          <button className="px-3 py-1.5 rounded-md text-sm font-medium text-white glass-soft flex items-center space-x-2">
            <Activity className="h-4 w-4 text-cyan-300" />
            <span>Dashboard</span>
          </button>
          <button className="px-3 py-1.5 rounded-md text-sm font-medium text-slate-400 hover:text-white hover:bg-slate-800/50 flex items-center space-x-2">
            <Bot className="h-4 w-4" />
            <span>Agents</span>
          </button>
          <button className="px-3 py-1.5 rounded-md text-sm font-medium text-slate-400 hover:text-white hover:bg-slate-800/50 flex items-center space-x-2">
            <Database className="h-4 w-4" />
            <span>Resources</span>
          </button>
          <button className="px-3 py-1.5 rounded-md text-sm font-medium text-slate-400 hover:text-white hover:bg-slate-800/50 flex items-center space-x-2">
            <ScrollText className="h-4 w-4" />
            <span>Policies</span>
          </button>
          <button className="px-3 py-1.5 rounded-md text-sm font-medium text-slate-400 hover:text-white hover:bg-slate-800/50 flex items-center space-x-2">
            <CheckSquare className="h-4 w-4" />
            <span>Approvals</span>
          </button>
        </nav>

        <div className="flex items-center space-x-3 text-xs">
          <div className="flex items-center space-x-1.5 bg-emerald-500/10 text-emerald-400 px-2.5 py-1 rounded-full border border-emerald-500/20">
            <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 animate-pulse"></span>
            <span>Phase 0 Ready</span>
          </div>
        </div>
      </div>
    </header>
  );
};
