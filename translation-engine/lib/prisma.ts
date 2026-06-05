import { PrismaClient } from "prismaclient";

export async function getPrisma(): Promise<PrismaClient> {
    const prisma = new PrismaClient();
    await prisma.$connect();
    return prisma;
}
