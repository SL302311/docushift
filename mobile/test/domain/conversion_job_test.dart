import 'package:test/test.dart';

import 'package:docushift_mobile/domain/models/conversion_error.dart';
import 'package:docushift_mobile/domain/models/conversion_job.dart';
import 'package:docushift_mobile/domain/models/conversion_result.dart';

void main() {
  group('ConversionJob', () {
    test('默认状态为 queued', () {
      const job = ConversionJob(
        id: 'job-1',
        inputPaths: ['test.pdf'],
        targetFormat: 'png',
      );
      expect(job.state, JobState.queued);
    });

    test('copyWith 更新状态', () {
      const job = ConversionJob(
        id: 'job-1',
        inputPaths: ['test.pdf'],
        targetFormat: 'png',
      );
      final updated = job.copyWith(state: JobState.running, progress: 0.5);
      expect(updated.state, JobState.running);
      expect(updated.progress, 0.5);
    });

    test('copyWith 不改变未指定字段', () {
      const job = ConversionJob(
        id: 'job-1',
        inputPaths: ['test.pdf'],
        targetFormat: 'png',
      );
      final updated = job.copyWith(state: JobState.succeeded);
      expect(updated.id, 'job-1');
      expect(updated.inputPaths, ['test.pdf']);
      expect(updated.targetFormat, 'png');
    });

    test('isFinished 对结束态返回 true', () {
      expect(
        const ConversionJob(
          id: '1', inputPaths: ['a.pdf'], targetFormat: 'png',
          state: JobState.succeeded,
        ).isFinished,
        true,
      );
      expect(
        const ConversionJob(
          id: '2', inputPaths: ['a.pdf'], targetFormat: 'png',
          state: JobState.failed,
        ).isFinished,
        true,
      );
      expect(
        const ConversionJob(
          id: '3', inputPaths: ['a.pdf'], targetFormat: 'png',
          state: JobState.cancelled,
        ).isFinished,
        true,
      );
    });

    test('isFinished 对运行中态返回 false', () {
      expect(
        const ConversionJob(
          id: '1', inputPaths: ['a.pdf'], targetFormat: 'png',
          state: JobState.running,
        ).isFinished,
        false,
      );
      expect(
        const ConversionJob(
          id: '2', inputPaths: ['a.pdf'], targetFormat: 'png',
          state: JobState.queued,
        ).isFinished,
        false,
      );
    });

    test('counts 计算正确', () {
      final job = ConversionJob(
        id: 'job-1',
        inputPaths: ['a.pdf', 'b.pdf', 'c.pdf'],
        targetFormat: 'png',
        state: JobState.partiallyFailed,
        results: [
          ConversionResult(
            outputPath: '/out/a.png',
            sizeBytes: 1024,
            completedAt: DateTime.now(),
          ),
          ConversionResult(
            outputPath: '/out/b.png',
            sizeBytes: 2048,
            completedAt: DateTime.now(),
          ),
        ],
        errors: [
          const ConversionException(
            code: ErrorCode.conversionFailed,
            message: '转换失败',
          ),
        ],
      );
      expect(job.totalCount, 3);
      expect(job.successCount, 2);
      expect(job.failureCount, 1);
    });
  });

  group('ConversionResult', () {
    test('sizeDescription 格式化正确', () {
      final r1 = ConversionResult(
        outputPath: '/out/a.png',
        sizeBytes: 500,
        completedAt: DateTime.now(),
      );
      expect(r1.sizeDescription, '500 B');

      final r2 = ConversionResult(
        outputPath: '/out/b.png',
        sizeBytes: 2048,
        completedAt: DateTime.now(),
      );
      expect(r2.sizeDescription, '2.0 KB');

      final r3 = ConversionResult(
        outputPath: '/out/c.png',
        sizeBytes: 2097152,
        completedAt: DateTime.now(),
      );
      expect(r3.sizeDescription, '2.0 MB');
    });

    test('isValid 对空文件返回 false', () {
      final r = ConversionResult(
        outputPath: '/out/empty.png',
        sizeBytes: 0,
        completedAt: DateTime.now(),
      );
      expect(r.isValid, false);
    });
  });

  group('ConversionException', () {
    test('快捷构造 fileNotFound', () {
      final e = ConversionException.fileNotFound('/no/file.pdf');
      expect(e.code, ErrorCode.fileNotFound);
      expect(e.message, contains('文件不存在'));
    });

    test('快捷构造 unsupportedFormat', () {
      final e = ConversionException.unsupportedFormat('mp4');
      expect(e.code, ErrorCode.unsupportedFormat);
      expect(e.message, contains('不支持的格式'));
    });
  });
}
