# Consumer Migration Guide: Java 8 to Java 17

## Overview

The Fusion Java SDK has been migrated from Java 8 to Java 17. This guide helps consumers migrate their applications to use the new Java 17-compatible version.

## What Changed

### For Consumers
- **Java Version Requirement**: Applications must now use Java 17 or higher
- **Public API**: No breaking changes - the public API remains backward compatible
- **Behavior**: No functional changes - SDK behavior is preserved
- **Performance**: Potential performance improvements from Java 17

### For Developers
- **Build System**: Updated to target Java 17
- **Dependencies**: Updated to Java 17-compatible versions
- **Code Quality**: Modernized language constructs and deprecated API usage

## Migration Steps

### 1. Update Java Version

**Before** (Java 8):
```xml
<properties>
    <maven.compiler.source>1.8</maven.compiler.source>
    <maven.compiler.target>1.8</maven.compiler.target>
</properties>
```

**After** (Java 17):
```xml
<properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
</properties>
```

### 2. Update SDK Version

Update your dependency to the latest Java 17-compatible version:

**Maven:**
```xml
<dependency>
    <groupId>io.github.jpmorganchase.fusion</groupId>
    <artifactId>fusion-sdk</artifactId>
    <version>0.0.19-SNAPSHOT</version>
</dependency>
```

**Gradle:**
```groovy
implementation 'io.github.jpmorganchase.fusion:fusion-sdk:0.0.19-SNAPSHOT'
```

### 3. Test Your Application

After updating, run your application's test suite to ensure compatibility:

```bash
mvn test
```

Or with Gradle:
```bash
gradle test
```

### 4. Monitor for Issues

Monitor your application for any runtime issues during the initial period after migration. Common issues to watch for:

- **Class Loading Issues**: Ensure no Java 8-specific libraries are in use
- **Performance Changes**: Java 17 may have different performance characteristics
- **Security Policies**: Java 17 has stricter security settings

## Compatibility Notes

### Backward Compatibility
- **Public API**: The public API remains fully backward compatible
- **Method Signatures**: No changes to method signatures
- **Return Types**: No changes to return types
- **Exception Types**: No changes to exception types

### Breaking Changes
- **None** - This migration is designed to be non-breaking for consumers

### Dependencies
- **AWS CRT**: Requires Java 11+ (already compatible with Java 17)
- **Lombok**: Requires Java 8+ (already compatible with Java 17)
- **Gson**: Requires Java 8+ (already compatible with Java 17)

## Benefits of Migration

### Security
- **Latest Security Patches**: Java 17 includes the latest security updates
- **Enhanced Security Features**: Improved security policies and enforcement
- **Vulnerability Fixes**: Protection against known vulnerabilities

### Performance
- **Improved Garbage Collection**: Better GC algorithms (G1GC, ZGC)
- **JIT Optimizations**: Enhanced just-in-time compilation
- **Memory Management**: Improved memory management and efficiency

### Developer Experience
- **Modern Language Features**: Access to Java 17 language features
- **Better Tooling**: Improved IDE and tooling support
- **Long-term Support**: Java 17 is an LTS release with long-term support

## Rollback Plan

If you encounter issues that cannot be resolved, you can rollback to the Java 8 version:

1. Revert Java version to 1.8 in your build configuration
2. Downgrade to the previous SDK version
3. Report the issue to the Fusion SDK team

## Support

If you encounter any issues during migration:

1. **Check Documentation**: Review this guide and the main README
2. **Review Error Messages**: Look for Java version-specific error messages
3. **Contact Support**: Reach out to the Fusion SDK support team
4. **Report Issues**: Report bugs or compatibility issues via GitHub Issues

## Next Steps

After successful migration:

1. **Update Your Documentation**: Update your internal documentation to reflect Java 17 requirement
2. **Update CI/CD Pipelines**: Ensure your CI/CD pipelines use Java 17
3. **Monitor Production**: Monitor your production environment for any issues
4. **Plan Future Upgrades**: Consider future Java version upgrades

## Additional Resources

- [Java 17 Release Notes](https://www.oracle.com/java/technologies/javase/17-relnotes.html)
- [Java 17 Migration Guide](https://docs.oracle.com/en/java/javase/17/migrate/)
- [Fusion SDK Documentation](https://github.com/jpmorganchase/fusion-java-sdk)
- [Fusion Platform](https://fusion.jpmorgan.com/)

## FAQ

### Q: Can I still use this SDK with Java 8?
A: No, the SDK now requires Java 17 or higher. You must upgrade your Java version.

### Q: Will my existing code break?
A: No, the public API remains backward compatible. Only the Java version requirement has changed.

### Q: Do I need to change my code?
A: No code changes are required, only build configuration updates.

### Q: What if I have issues?
A: Please report issues via GitHub Issues and include details about your environment and the specific problem.

### Q: Is this migration worth it?
A: Yes, the migration provides significant security, performance, and long-term support benefits.

## Conclusion

Migrating to the Java 17 version of the Fusion SDK provides significant benefits while maintaining backward compatibility. Follow the steps in this guide to ensure a smooth migration process.
