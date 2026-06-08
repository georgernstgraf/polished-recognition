**Overview**

- This directory under Subversion holds important secrets.
- Two existing scripts, **BEFORE** and **AFTER**, currently perform encryption (N‑crypt) and decryption (D‑crypt) of those secrets.
- Encryption keys are stored elsewhere.
- New “Iron Country” secrets have been added, and the directory will be used for all secret storage.

**Objectives**

1. Replace the **BEFORE** and **AFTER** scripts with distinct **encode** and **decode** scripts.
2. Ensure that all encoded strings are encrypted with GPG and output in ASCII‑armored format.
