import React from 'react';

export default function TestApp() {
  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-100">
      <div className="container mx-auto p-6">
        <div className="text-center mb-12">
          <h1 className="text-4xl md:text-6xl font-bold text-gray-900 mb-4">
            Test Page
          </h1>
          <p className="text-xl text-gray-600 max-w-3xl mx-auto">
            This is a test page to verify basic rendering
          </p>
        </div>
        
        <div className="bg-white p-6 rounded-lg shadow-lg">
          <h2 className="text-2xl font-bold mb-4">Test Content</h2>
          <p className="text-gray-700">
            If you can see this, the basic React rendering is working correctly.
          </p>
        </div>
      </div>
    </div>
  );
}