import { db } from '../src/lib/db';

async function main() {
  // Create sample broadcasts
  const broadcasts = [
    {
      senderId: 'admin-001',
      senderName: 'System Administrator',
      content: 'Welcome to the broadcast messaging system! This is a system-wide announcement.',
      targetType: 'ALL',
      targetIds: '[]',
      priority: 'HIGH',
      category: 'SYSTEM',
      totalTargeted: 400000,
      totalDelivered: 395000,
      totalRead: 380000,
    },
    {
      senderId: 'security-001',
      senderName: 'Security Team',
      content: 'Important security update: Please review the new security policies.',
      targetType: 'ALL',
      targetIds: '[]',
      priority: 'URGENT',
      category: 'SECURITY',
      totalTargeted: 400000,
      totalDelivered: 398000,
      totalRead: 350000,
    }
  ];

  for (const broadcast of broadcasts) {
    await db.broadcast.create({
      data: broadcast
    });
  }

  console.log('Database seeded successfully!');
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(async () => {
    await db.$disconnect();
  });