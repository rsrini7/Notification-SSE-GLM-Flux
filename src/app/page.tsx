'use client';

import React, { useState } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Badge } from '@/components/ui/badge';
import BroadcastAdminPanel from '@/components/broadcast/BroadcastAdminPanel';
import BroadcastUserPanel from '@/components/broadcast/BroadcastUserPanel';
import { 
  MessageSquare, 
  Users, 
  Zap, 
  Database, 
  Cloud, 
  Shield,
  BarChart3,
  Settings,
  Bell,
  Send
} from 'lucide-react';

export default function Home() {
  const [activeTab, setActiveTab] = useState('overview');

  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-100">
      <div className="container mx-auto p-6">
        {/* Header */}
        <div className="text-center mb-12">
          <h1 className="text-4xl md:text-6xl font-bold text-gray-900 mb-4">
            Broadcast Messaging System
          </h1>
          <p className="text-xl text-gray-600 max-w-3xl mx-auto">
            High-scale real-time messaging platform supporting 400,000+ users with 30,000+ concurrent connections
          </p>
          <div className="flex justify-center gap-4 mt-6">
            <Badge variant="secondary" className="flex items-center gap-2">
              <Zap className="h-4 w-4" />
              Real-time SSE
            </Badge>
            <Badge variant="secondary" className="flex items-center gap-2">
              <Database className="h-4 w-4" />
              h2 Database
            </Badge>
            <Badge variant="secondary" className="flex items-center gap-2">
              <Cloud className="h-4 w-4" />
              Kafka Events
            </Badge>
            <Badge variant="secondary" className="flex items-center gap-2">
              <Shield className="h-4 w-4" />
              Kubernetes Ready
            </Badge>
          </div>
        </div>

        {/* Main Content */}
        <Tabs value={activeTab} onValueChange={setActiveTab} className="space-y-6">
          <TabsList className="grid w-full grid-cols-4 max-w-2xl mx-auto">
            <TabsTrigger value="overview">Overview</TabsTrigger>
            <TabsTrigger value="admin">Admin Panel</TabsTrigger>
            <TabsTrigger value="user">User Panel</TabsTrigger>
            <TabsTrigger value="features">Features</TabsTrigger>
          </TabsList>

          <TabsContent value="overview" className="space-y-8">
            {/* Key Metrics */}
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
              <Card>
                <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
                  <CardTitle className="text-sm font-medium">Total Users</CardTitle>
                  <Users className="h-4 w-4 text-muted-foreground" />
                </CardHeader>
                <CardContent>
                  <div className="text-2xl font-bold">400,000+</div>
                  <p className="text-xs text-muted-foreground">Registered users</p>
                </CardContent>
              </Card>
              
              <Card>
                <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
                  <CardTitle className="text-sm font-medium">Concurrent Users</CardTitle>
                  <MessageSquare className="h-4 w-4 text-muted-foreground" />
                </CardHeader>
                <CardContent>
                  <div className="text-2xl font-bold">30,000+</div>
                  <p className="text-xs text-muted-foreground">Peak concurrent connections</p>
                </CardContent>
              </Card>
              
              <Card>
                <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
                  <CardTitle className="text-sm font-medium">Message Latency</CardTitle>
                  <Zap className="h-4 w-4 text-muted-foreground" />
                </CardHeader>
                <CardContent>
                  <div className="text-2xl font-bold">&lt;1s</div>
                  <p className="text-xs text-muted-foreground">Sub-second delivery</p>
                </CardContent>
              </Card>
              
              <Card>
                <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
                  <CardTitle className="text-sm font-medium">Uptime</CardTitle>
                  <Shield className="h-4 w-4 text-muted-foreground" />
                </CardHeader>
                <CardContent>
                  <div className="text-2xl font-bold">99.9%</div>
                  <p className="text-xs text-muted-foreground">High availability</p>
                </CardContent>
              </Card>
            </div>

            {/* Architecture Overview */}
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <Settings className="h-5 w-5" />
                  Architecture Overview
                </CardTitle>
                <CardDescription>
                  Modern microservice architecture built for scale and reliability
                </CardDescription>
              </CardHeader>
              <CardContent>
                <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
                  <div className="space-y-4">
                    <h3 className="font-semibold text-lg">Frontend</h3>
                    <ul className="space-y-2 text-sm">
                      <li className="flex items-center gap-2">
                        <div className="w-2 h-2 bg-blue-500 rounded-full"></div>
                        React 19 with TypeScript
                      </li>
                      <li className="flex items-center gap-2">
                        <div className="w-2 h-2 bg-blue-500 rounded-full"></div>
                        Real-time SSE connections
                      </li>
                      <li className="flex items-center gap-2">
                        <div className="w-2 h-2 bg-blue-500 rounded-full"></div>
                        Responsive UI components
                      </li>
                      <li className="flex items-center gap-2">
                        <div className="w-2 h-2 bg-blue-500 rounded-full"></div>
                        shadcn/ui component library
                      </li>
                    </ul>
                  </div>
                  
                  <div className="space-y-4">
                    <h3 className="font-semibold text-lg">Backend</h3>
                    <ul className="space-y-2 text-sm">
                      <li className="flex items-center gap-2">
                        <div className="w-2 h-2 bg-green-500 rounded-full"></div>
                        Spring Boot WebFlux (Reactive)
                      </li>
                      <li className="flex items-center gap-2">
                        <div className="w-2 h-2 bg-green-500 rounded-full"></div>
                        h2 Database with JDBC
                      </li>
                      <li className="flex items-center gap-2">
                        <div className="w-2 h-2 bg-green-500 rounded-full"></div>
                        Kafka event streaming
                      </li>
                      <li className="flex items-center gap-2">
                        <div className="w-2 h-2 bg-green-500 rounded-full"></div>
                        Caffeine high-performance cache
                      </li>
                    </ul>
                  </div>
                </div>
              </CardContent>
            </Card>

            {/* Key Features */}
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <BarChart3 className="h-5 w-5" />
                  Key Features
                </CardTitle>
              </CardHeader>
              <CardContent>
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                  <div className="space-y-2">
                    <div className="flex items-center gap-2">
                      <Bell className="h-5 w-5 text-blue-600" />
                      <h3 className="font-semibold">Real-time Delivery</h3>
                    </div>
                    <p className="text-sm text-gray-600">
                      Sub-second message delivery using Server-Sent Events (SSE)
                    </p>
                  </div>
                  
                  <div className="space-y-2">
                    <div className="flex items-center gap-2">
                      <Database className="h-5 w-5 text-green-600" />
                      <h3 className="font-semibold">Persistent Storage</h3>
                    </div>
                    <p className="text-sm text-gray-600">
                      h2 Database with admin and user-side message tracking
                    </p>
                  </div>
                  
                  <div className="space-y-2">
                    <div className="flex items-center gap-2">
                      <Cloud className="h-5 w-5 text-purple-600" />
                      <h3 className="font-semibold">Event Streaming</h3>
                    </div>
                    <p className="text-sm text-gray-600">
                      Kafka-based fan-out with at-least-once delivery semantics
                    </p>
                  </div>
                  
                  <div className="space-y-2">
                    <div className="flex items-center gap-2">
                      <Zap className="h-5 w-5 text-yellow-600" />
                      <h3 className="font-semibold">High Performance</h3>
                    </div>
                    <p className="text-sm text-gray-600">
                      Caffeine caching for low-latency operations and scalability
                    </p>
                  </div>
                  
                  <div className="space-y-2">
                    <div className="flex items-center gap-2">
                      <Shield className="h-5 w-5 text-red-600" />
                      <h3 className="font-semibold">High Availability</h3>
                    </div>
                    <p className="text-sm text-gray-600">
                      Kubernetes deployment with HPA and fault tolerance
                    </p>
                  </div>
                  
                  <div className="space-y-2">
                    <div className="flex items-center gap-2">
                      <BarChart3 className="h-5 w-5 text-indigo-600" />
                      <h3 className="font-semibold">Observability</h3>
                    </div>
                    <p className="text-sm text-gray-600">
                      Comprehensive monitoring, logging, and distributed tracing
                    </p>
                  </div>
                </div>
              </CardContent>
            </Card>
          </TabsContent>

          <TabsContent value="admin">
            <BroadcastAdminPanel />
          </TabsContent>

          <TabsContent value="user">
            <BroadcastUserPanel />
          </TabsContent>

          <TabsContent value="features" className="space-y-8">
            {/* Technical Specifications */}
            <Card>
              <CardHeader>
                <CardTitle>Technical Specifications</CardTitle>
                <CardDescription>
                  Detailed technical capabilities and performance characteristics
                </CardDescription>
              </CardHeader>
              <CardContent>
                <div className="grid grid-cols-1 lg:grid-cols-2 gap-8">
                  <div className="space-y-6">
                    <h3 className="text-lg font-semibold">Performance Metrics</h3>
                    <div className="space-y-3">
                      <div className="flex justify-between items-center p-3 bg-gray-50 rounded">
                        <span>Message Throughput</span>
                        <Badge variant="outline">10,000+ msg/sec</Badge>
                      </div>
                      <div className="flex justify-between items-center p-3 bg-gray-50 rounded">
                        <span>Connection Latency</span>
                        <Badge variant="outline">&lt;100ms</Badge>
                      </div>
                      <div className="flex justify-between items-center p-3 bg-gray-50 rounded">
                        <span>Database Operations</span>
                        <Badge variant="outline">1,000+ ops/sec</Badge>
                      </div>
                      <div className="flex justify-between items-center p-3 bg-gray-50 rounded">
                        <span>Cache Hit Rate</span>
                        <Badge variant="outline">95%+</Badge>
                      </div>
                    </div>
                  </div>
                  
                  <div className="space-y-6">
                    <h3 className="text-lg font-semibold">Scalability Limits</h3>
                    <div className="space-y-3">
                      <div className="flex justify-between items-center p-3 bg-gray-50 rounded">
                        <span>Max Users</span>
                        <Badge variant="outline">500,000</Badge>
                      </div>
                      <div className="flex justify-between items-center p-3 bg-gray-50 rounded">
                        <span>Max Concurrent Connections</span>
                        <Badge variant="outline">50,000</Badge>
                      </div>
                      <div className="flex justify-between items-center p-3 bg-gray-50 rounded">
                        <span>Max Pod Replicas</span>
                        <Badge variant="outline">20</Badge>
                      </div>
                      <div className="flex justify-between items-center p-3 bg-gray-50 rounded">
                        <span>Kafka Partitions</span>
                        <Badge variant="outline">10</Badge>
                      </div>
                    </div>
                  </div>
                </div>
              </CardContent>
            </Card>

            {/* Integration Points */}
            <Card>
              <CardHeader>
                <CardTitle>Integration Capabilities</CardTitle>
                <CardDescription>
                  Ready for integration with enterprise systems and services
                </CardDescription>
              </CardHeader>
              <CardContent>
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                  <div className="text-center p-4 border rounded-lg">
                    <Send className="h-8 w-8 mx-auto mb-2 text-blue-600" />
                    <h3 className="font-semibold">REST APIs</h3>
                    <p className="text-sm text-gray-600 mt-1">
                      Full RESTful API for all operations
                    </p>
                  </div>
                  
                  <div className="text-center p-4 border rounded-lg">
                    <Bell className="h-8 w-8 mx-auto mb-2 text-green-600" />
                    <h3 className="font-semibold">Webhooks</h3>
                    <p className="text-sm text-gray-600 mt-1">
                      Event-driven notifications
                    </p>
                  </div>
                  
                  <div className="text-center p-4 border rounded-lg">
                    <Database className="h-8 w-8 mx-auto mb-2 text-purple-600" />
                    <h3 className="font-semibold">Database Sync</h3>
                    <p className="text-sm text-gray-600 mt-1">
                      Real-time data synchronization
                    </p>
                  </div>
                </div>
              </CardContent>
            </Card>
          </TabsContent>
        </Tabs>
      </div>
    </div>
  );
}