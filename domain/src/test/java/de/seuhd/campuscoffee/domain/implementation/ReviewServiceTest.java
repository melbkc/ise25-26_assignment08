package de.seuhd.campuscoffee.domain.implementation;

import de.seuhd.campuscoffee.domain.configuration.ApprovalConfiguration;
import de.seuhd.campuscoffee.domain.exceptions.NotFoundException;
import de.seuhd.campuscoffee.domain.exceptions.ValidationException;
import de.seuhd.campuscoffee.domain.model.objects.Pos;
import de.seuhd.campuscoffee.domain.model.objects.Review;
import de.seuhd.campuscoffee.domain.model.objects.User;
import de.seuhd.campuscoffee.domain.ports.data.PosDataService;
import de.seuhd.campuscoffee.domain.ports.data.ReviewDataService;
import de.seuhd.campuscoffee.domain.ports.data.UserDataService;
import de.seuhd.campuscoffee.domain.tests.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Objects;

import static de.seuhd.campuscoffee.domain.tests.TestFixtures.getApprovalConfiguration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * Unit and integration tests for the operations related to reviews.
 */
@ExtendWith(MockitoExtension.class)
public class ReviewServiceTest {
    private final ApprovalConfiguration approvalConfiguration = getApprovalConfiguration();

    @Mock
    private ReviewDataService reviewDataService;

    @Mock
    private UserDataService userDataService;

    @Mock
    private PosDataService posDataService;

    private ReviewServiceImpl reviewService;

    @BeforeEach
    void beforeEach() {
        reviewService = new ReviewServiceImpl(
                reviewDataService, userDataService, posDataService, approvalConfiguration
        );
    }

    @Test
    void approvalFailsIfUserIsAuthor() {
        // given
        Review review = TestFixtures.getReviewFixtures().getFirst();
        assertNotNull(review.author().id());
        when(userDataService.getById(review.author().id())).thenReturn(review.author());
        assertNotNull(review.id());
        when(reviewDataService.getById(review.id())).thenReturn(review);

        // when, then
        assertThrows(ValidationException.class, () -> reviewService.approve(review, review.author().getId()));
        verify(userDataService).getById(review.author().id());
        verify(reviewDataService).getById(review.getId());
    }

    @Test
    void approvalSuccessfulIfUserIsNotAuthor() {
        // given
        Review review = TestFixtures.getReviewFixtures().getFirst().toBuilder()
                .approvalCount(2)
                .approved(false)
                .build();
        User user = TestFixtures.getUserFixtures().getLast();
        assertNotNull(user.getId());
        when(userDataService.getById(user.getId())).thenReturn(user);
        assertNotNull(review.getId());
        when(reviewDataService.getById(review.getId())).thenReturn(review);
        when(reviewDataService.upsert(any(Review.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        Review approvedReview = reviewService.approve(review, user.getId());

        // then
        verify(userDataService).getById(user.getId());
        verify(reviewDataService).getById(review.getId());
        verify(reviewDataService).upsert(any(Review.class));
        assertThat(approvedReview.approvalCount()).isEqualTo(review.approvalCount() + 1);
        assertThat(approvedReview.approved()).isTrue();
    }

    @Test
    void getApprovedByPos() {
        // given
        Pos pos = TestFixtures.getPosFixtures().getFirst();
        assertNotNull(pos.getId());
        List<Review> reviews = TestFixtures.getReviewFixtures().stream()
                .map(review -> review.toBuilder()
                        .pos(pos)
                        .approvalCount(3)
                        .approved(true)
                        .build())
                .toList();
        when(posDataService.getById(pos.getId())).thenReturn(pos);
        when(reviewDataService.filter(pos, true)).thenReturn(reviews);

        // when
        List<Review> retrievedReviews = reviewService.filter(Objects.requireNonNull(pos.getId()), true);

        // then
        verify(posDataService).getById(pos.getId());
        verify(reviewDataService).filter(pos, true);
        assertThat(retrievedReviews).hasSize(reviews.size());
    }

    @Test
    void createReviewPosDoesNotExistException() {
        // given
        Review review = TestFixtures.getReviewFixtures().getFirst();
        assertNotNull(review.pos().getId());
        when(posDataService.getById(review.pos().getId())).thenThrow(
                new NotFoundException(review.pos().getClass(), review.pos().getId())
        );

        // when, then
        assertThrows(NotFoundException.class, () -> reviewService.upsert(review));
        verify(posDataService).getById(review.pos().getId());
    }

    @Test
    void userCannotCreateMoreThanOneReviewPerPos() {
        // given
        Review review = TestFixtures.getReviewFixtures().getFirst();
        Pos pos = review.pos();
        User author = review.author();
        assertNotNull(pos.getId());

        when(posDataService.getById(pos.getId())).thenReturn(pos);
        when(reviewDataService.filter(pos, author)).thenReturn(List.of(review));

        // when, then
        assertThrows(ValidationException.class, () -> reviewService.upsert(review)
        );
        verify(posDataService).getById(pos.getId());
        verify(reviewDataService).filter(pos, author);
    }

    @Test
    void testUpdateApprovalStatusForUnapprovedReview() {
        // given
        Review unapprovedReview = TestFixtures.getReviewFixtures().getFirst().toBuilder()
                .approvalCount(2)
                .approved(false)
                .build();

        // when
        Review updatedReview = reviewService.updateApprovalStatus(unapprovedReview);

        // then
        assertFalse(updatedReview.approved());

        // when
        Review approvedReview = unapprovedReview.toBuilder()
                .approvalCount(approvalConfiguration.minCount())
                .build();

        // when
        updatedReview = reviewService.updateApprovalStatus(approvedReview);

        // then
        assertTrue(updatedReview.approved());
    }

    @Test
    void upsertNewReview() {
        //given
        Review review = TestFixtures.getReviewFixtures().getFirst().toBuilder()
                .id(null)
                .build();
        when(posDataService.getById(review.pos().getId())).thenReturn(review.pos());
        when(reviewDataService.filter(review.pos(), review.author())).thenReturn(List.of());
        when(reviewDataService.upsert(review)).thenReturn(review.toBuilder().id(42L).build());

        //when
        Review result = reviewService.upsert(review);

        //then
        assertNotNull(result.getId());
        assertThat(result.getId()).isEqualTo(42L);
        verify(reviewDataService).upsert(review);
    }

    @Test
    void approveIncrementsCountOfAlreadyApprovedReview() {
        //given
        Review review = TestFixtures.getReviewFixtures().getFirst().toBuilder()
                .approvalCount(approvalConfiguration.minCount())
                .approved(true)
                .build();
        User user = TestFixtures.getUserFixtures().getLast();
        when(userDataService.getById(user.getId())).thenReturn(user);
        when(reviewDataService.getById(review.getId())).thenReturn(review);
        when(reviewDataService.upsert(any(Review.class)))
                .thenAnswer(i -> i.getArgument(0));

        //when
        Review result = reviewService.approve(review, user.getId());

        //then
        assertTrue(result.approved());
        assertThat(result.approvalCount()).isEqualTo(approvalConfiguration.minCount() +1);
    }

    @Test
    void filterReturnUnapprovedReviews() {
        // given
        Pos pos = TestFixtures.getPosFixtures().getFirst();
        List<Review> reviews = TestFixtures.getReviewFixtures()
                .stream()
                .map(review -> review.toBuilder().pos(pos).approved(false).build())
                .toList();
        when(posDataService.getById(pos.getId())).thenReturn(pos);
        when(reviewDataService.filter(pos, false)).thenReturn(reviews);

        // when
        List<Review> result = reviewService.filter(pos.getId(), false);

        // then
        assertThat(result).allMatch(review -> !review.approved());
    }

    @Test
    void approveReviewReachesThreshold() {
        //given
        Review review = TestFixtures.getReviewFixtures().getFirst().toBuilder()
                .approvalCount(approvalConfiguration.minCount() -1)
                .approved(false)
                .build();
        User user = TestFixtures.getUserFixtures().getLast();
        when(userDataService.getById(user.getId())).thenReturn(user);
        when(reviewDataService.getById(review.getId())).thenReturn(review);
        when(reviewDataService.upsert(any(Review.class)))
                .thenAnswer(i -> i.getArgument(0));

        //when
        Review result = reviewService.approve(review, user.getId());

        //then
        assertTrue(result.approved());
        assertThat(result.approvalCount()).isEqualTo(approvalConfiguration.minCount());
    }
}