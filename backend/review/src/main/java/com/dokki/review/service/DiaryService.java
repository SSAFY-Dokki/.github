package com.dokki.review.service;


import com.dokki.review.client.BookClient;
import com.dokki.review.config.exception.CustomException;
import com.dokki.review.dto.request.DiaryRequestDto;
import com.dokki.review.dto.response.DiaryResponseDto;
import com.dokki.review.entity.DiaryEntity;
import com.dokki.review.repository.DiaryRepository;
import com.dokki.util.book.dto.response.BookSimpleResponseDto;
import com.dokki.util.book.dto.response.CollectionSimpleResponseDto;
import com.dokki.util.common.enums.DefaultEnum;
import com.dokki.util.common.error.ErrorCode;
import com.dokki.util.common.utils.FileUtils;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;


@Slf4j
@Service
@RequiredArgsConstructor
public class DiaryService {

	private final DiaryRepository diaryRepository;
	private final BookClient bookClient;


	/**
	 * DiaryResponseDto를 생성
	 *
	 * @param diary
	 * @return
	 */
	private static DiaryResponseDto getDiaryResponseDto(DiaryEntity diary, BookSimpleResponseDto bookSimpleInfo) {
		return DiaryResponseDto.builder()
			.bookId(diary.getBookId())
			.bookTitle(bookSimpleInfo.getBookTitle())
			.bookAuthor(bookSimpleInfo.getBookAuthor() == null ? DefaultEnum.BOOK_BOOK_AUTHOR.getValue() : bookSimpleInfo.getBookAuthor())
			.bookPublishYear(bookSimpleInfo.getBookPublishYear() == null ? DefaultEnum.BOOK_BOOK_PUBLISH_YEAR.getValue() : bookSimpleInfo.getBookPublishYear())
			.bookPublisher(bookSimpleInfo.getBookPublisher() == null ? DefaultEnum.BOOK_BOOK_PUBLISHER.getValue() : bookSimpleInfo.getBookPublisher())
			.bookCoverPath(bookSimpleInfo.getBookCoverPath())
			.diaryId(diary.getId())
			.diaryImagePath(FileUtils.getAbsoluteFilePath(diary.getDiaryImagePath()))
			.diaryContent(diary.getContent())
			.created(diary.getCreatedAt())
			.build();
	}


	/**
	 * bookSimpleInfo 를 받아오지 못한 경우, default 값으로 DiaryResponseDto 생성
	 *
	 * @param diary
	 * @return
	 */
	private static DiaryResponseDto getDiaryResponseDto(DiaryEntity diary) {
		BookSimpleResponseDto bookSimpleResponseDto = BookSimpleResponseDto.builder()
			.bookId(diary.getBookId())
			.bookAuthor(DefaultEnum.BOOK_BOOK_AUTHOR.getValue())
			.bookTitle(DefaultEnum.BOOK_BOOK_TITLE.getValue())
			.bookPublisher(DefaultEnum.BOOK_BOOK_PUBLISH_YEAR.getValue())
			.bookPublishYear(DefaultEnum.BOOK_BOOK_PUBLISH_YEAR.getValue())
			.bookCoverPath(DefaultEnum.BOOK_BOOK_COVER_IMAGE_PATH.getValue())
			.build();
		return getDiaryResponseDto(diary, bookSimpleResponseDto);
	}


	private boolean isSameUser(Long userId, Long commentId) {
		if (userId != commentId) {
			throw new CustomException(ErrorCode.INVALID_REQUEST);
		}
		return true;
	}


	public DiaryEntity getPresentDiary(Long diaryId) {
		return diaryRepository.findById(diaryId).orElseThrow(() -> new CustomException(ErrorCode.NOTFOUND_RESOURCE));
	}


	/**
	 * 감정 일기 생성
	 *
	 * @param bookId
	 * @param diaryRequestDto
	 */
	public void createDiary(Long userId, String bookId, DiaryRequestDto diaryRequestDto) {
		Long bookStatusId;
		try {
			// bookStatusId 조회
			CollectionSimpleResponseDto collectionSimpleInfo = bookClient.getCollectionSimple(bookId);
			bookStatusId = collectionSimpleInfo.getBookStatusId();
		} catch (FeignException e) {
			//			e.printStackTrace();
			log.error(e.getMessage());
			// create api이므로 조회 실패 시 exception 반환
			throw e;
		}
		// 해당 책에 대해 이미 일기를 작성했다면 예외
		if (diaryRepository.existsByUserIdAndBookId(userId, bookId)) {
			throw new CustomException(ErrorCode.DUPLICATE_RESOURCE);
		}
		// 생성
		DiaryEntity diary = DiaryEntity.builder()
			.userId(userId)
			.bookId(bookId)
			.bookStatusId(bookStatusId)
			.content(diaryRequestDto.getContent())
			.diaryImagePath(diaryRequestDto.getDiaryImagePath())
			.build();
		diaryRepository.save(diary);
	}


	/**
	 * 감정 일기 수정
	 *
	 * @param diaryId
	 * @param diaryRequestDto
	 */
	public void modifyDiary(Long userId, Long diaryId, DiaryRequestDto diaryRequestDto) {
		// diary 조회
		DiaryEntity diary = getPresentDiary(diaryId);

		// user 체크
		if (isSameUser(userId, diary.getUserId()) == false) return;

		// 수정
		diary.updateContent(diaryRequestDto.getContent());
		diary.updateDiaryImagePath(diaryRequestDto.getDiaryImagePath());
		diaryRepository.save(diary);
	}


	/**
	 * 감정 일기 삭제
	 *
	 * @param diaryId
	 */
	public void deleteDiary(Long userId, Long diaryId) {
		// diary 조회
		DiaryEntity diary = getPresentDiary(diaryId);

		// user 체크
		if (isSameUser(userId, diary.getUserId()) == false) return;

		// 삭제
		diaryRepository.delete(diary);
	}


	/**
	 * 책에 대해 작성한 감정 일기 조회
	 *
	 * @param bookId
	 * @return
	 */
	public DiaryResponseDto getDiaryByBook(Long userId, String bookId) {
		DiaryEntity diary = diaryRepository.findByUserIdAndBookId(userId, bookId).orElseThrow(() -> new CustomException(ErrorCode.NOTFOUND_RESOURCE));
		BookSimpleResponseDto bookSimpleResponseDto = null;
		try {
			bookSimpleResponseDto = bookClient.getBookSimple(bookId);
		} catch (FeignException e) {
			log.error(e.getMessage());
		}
		if (bookSimpleResponseDto == null)
			return getDiaryResponseDto(diary);
		else return getDiaryResponseDto(diary, bookSimpleResponseDto);
	}


	/**
	 * 내가 작성한 감정 일기 목록 조회
	 *
	 * @param pageable
	 * @return
	 */
	public Slice<DiaryResponseDto> getDiaryList(Long userId, Pageable pageable) {
		Slice<DiaryEntity> diaryEntitySlice = diaryRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);

		// bookId를 list로 생성
		List<String> bookIdList = diaryEntitySlice.map(d -> d.getBookId()).toList();
		Slice<DiaryResponseDto> diaryResponseDtoSlice;

		// book 정보 조회, book 정보를 diary와 연결
		try {
			List<BookSimpleResponseDto> bookSimpleInfoList = bookClient.getBookSimple(bookIdList);
			AtomicInteger counter = new AtomicInteger(0);
			diaryResponseDtoSlice = diaryEntitySlice.map(
				d -> {
					int idx = counter.getAndIncrement();
					return getDiaryResponseDto(d, bookSimpleInfoList.get(idx));
				}
			);
		} catch (FeignException e) {
			log.error(e.getMessage());
			diaryResponseDtoSlice = diaryEntitySlice.map(
				d -> getDiaryResponseDto(d)
			);
		}
   
		return diaryResponseDtoSlice;
	}

}
