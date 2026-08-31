'use strict';

const crypto = require('crypto');

function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(String(password), salt, 64).toString('hex');
  return `scrypt$${salt}$${hash}`;
}

function verifyPassword(password, stored) {
  if (!stored) return false;
  // allow plain demo passwords during migration/seed fallback
  if (!String(stored).startsWith('scrypt$')) {
    return String(password) === String(stored);
  }
  const parts = String(stored).split('$');
  if (parts.length !== 3) return false;
  const salt = parts[1];
  const expect = parts[2];
  const hash = crypto.scryptSync(String(password), salt, 64).toString('hex');
  try {
    return crypto.timingSafeEqual(Buffer.from(hash, 'hex'), Buffer.from(expect, 'hex'));
  } catch (_) {
    return false;
  }
}

module.exports = { hashPassword, verifyPassword };
